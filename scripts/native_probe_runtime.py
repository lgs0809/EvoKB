"""Frozen Spring Boot artifacts and bounded JDK commands for component probes.

This module never reads production source, starts a model, seeds a database or
decides whether a probe passed. Callers own fixtures, overlays and assertions.
"""
from dataclasses import dataclass
from functools import lru_cache
import hashlib
import io
import os
from pathlib import Path, PurePosixPath
import shutil
import subprocess
import zipfile

RUNTIME_SOURCE_SHA256 = hashlib.sha256(Path(__file__).read_bytes()).hexdigest()


def sha256(data):
    return hashlib.sha256(data).hexdigest()


@lru_cache(maxsize=1)
def java17_bin():
    """Use the probe override, JAVA_HOME or the host's verified Java17."""
    configured = os.environ.get('EKB_PROBE_JAVA_HOME') or os.environ.get('JAVA_HOME')
    if configured:
        result = Path(configured).expanduser().resolve() / 'bin'
    elif Path('/usr/libexec/java_home').is_file():
        home = subprocess.check_output(['/usr/libexec/java_home', '-v', '17'],
                                       text=True, timeout=10).strip()
        result = Path(home) / 'bin'
    else:
        compiler = shutil.which('javac')
        if not compiler:
            raise ValueError('Java17 is required; configure EKB_PROBE_JAVA_HOME or JAVA_HOME')
        result = Path(compiler).resolve().parent
    version = subprocess.check_output([str(result / 'javac'), '-version'],
                                     text=True, stderr=subprocess.STDOUT, timeout=10)
    if not version.startswith('javac 17.'):
        raise ValueError('Expected Java17, received ' + version.strip())
    return result


@dataclass(frozen=True)
class ArtifactClasspath:
    boot: Path
    libraries: tuple
    class_hashes: dict
    library_hashes: dict

    @property
    def value(self):
        return os.pathsep.join([str(self.boot), *(str(path) for path in self.libraries)])

    @property
    def libraries_only(self):
        return os.pathsep.join(str(path) for path in self.libraries)


class FrozenBootArtifact:
    """Read once: extracted classes and recorded SHA always use identical bytes."""
    def __init__(self, path, expected_sha256=None):
        self.path = Path(path).resolve()
        self.data = self.path.read_bytes()
        self.sha256 = sha256(self.data)
        if expected_sha256 is not None and self.sha256 != expected_sha256:
            raise ValueError('Artifact SHA differs from the verified immutable artifact')

    def extract(self, directory):
        root = Path(directory)
        boot, libraries = root / 'boot', root / 'lib'
        boot.mkdir(parents=True, exist_ok=False)
        libraries.mkdir(parents=True, exist_ok=False)
        paths, class_hashes, library_hashes = [], {}, {}
        with zipfile.ZipFile(io.BytesIO(self.data)) as archive:
            for name in archive.namelist():
                parts = PurePosixPath(name)
                if parts.is_absolute() or '..' in parts.parts or '\\' in name:
                    raise ValueError('Unsafe archive entry: ' + name)
                if name.startswith('BOOT-INF/classes/') and not name.endswith('/'):
                    target = boot / name[len('BOOT-INF/classes/'):]
                    if name in class_hashes:
                        raise ValueError('Duplicate artifact class/resource: ' + name)
                    content = archive.read(name)
                    target.parent.mkdir(parents=True, exist_ok=True)
                    target.write_bytes(content)
                    class_hashes[name] = sha256(content)
                elif name.startswith('BOOT-INF/lib/') and name.endswith('.jar'):
                    target = libraries / parts.name
                    if target.name in library_hashes:
                        raise ValueError('Duplicate artifact dependency: ' + target.name)
                    content = archive.read(name)
                    target.write_bytes(content)
                    paths.append(target)
                    library_hashes[target.name] = sha256(content)
        if not paths:
            raise ValueError('Expected a Spring Boot artifact with frozen dependencies')
        return ArtifactClasspath(boot, tuple(paths), class_hashes, library_hashes)

    def unchanged(self):
        return sha256(self.path.read_bytes()) == self.sha256


def _retained_command(command, log, timeout, env=None):
    log = Path(log)
    if log.exists():
        raise ValueError('Fresh command log required; preserve prior failures')
    log.parent.mkdir(parents=True, exist_ok=True)
    try:
        result = subprocess.run(command, capture_output=True, text=True,
                                env=env, timeout=timeout)
    except subprocess.TimeoutExpired as error:
        def decoded(value):
            return value.decode(errors='replace') if isinstance(value, bytes) else value or ''
        log.write_text(decoded(error.stdout) + decoded(error.stderr) + '\nCOMMAND_TIMEOUT\n')
        raise
    log.write_text(result.stdout + result.stderr)
    return result


def compile_java(classpath, sources, classes, log, *, timeout=90):
    classes = Path(classes)
    classes.mkdir(parents=True, exist_ok=True)
    return _retained_command([str(java17_bin() / 'javac'), '-J-Xmx128m',
        '-J-XX:ActiveProcessorCount=2', '-cp', classpath, '-d', str(classes),
        *(str(path) for path in sources)], log, timeout)


def run_java(classpath, main_class, args, log, *, heap_mib=256, timeout=180,
             env=None, extra_jvm_args=()):
    if not 16 <= heap_mib <= 512 or timeout <= 0:
        raise ValueError('Explicit bounded native probe resources required')
    return _retained_command([str(java17_bin() / 'java'), '-Xmx%dm' % heap_mib,
        '-XX:ActiveProcessorCount=2', *extra_jvm_args, '-cp', classpath,
        main_class, *(str(arg) for arg in args)], log, timeout, env)
