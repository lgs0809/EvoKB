import { sha256 } from '@noble/hashes/sha2.js';
import { md5 } from '@noble/hashes/legacy.js';
import { bytesToHex } from '@noble/hashes/utils.js';

// Whole identity is incremental. The main UI never allocates the complete original in memory.
self.onmessage = async (event: MessageEvent<{ file: File; partSize: number }>) => {
  const { file, partSize } = event.data;
  try {
    const whole = sha256.create();
    const parts: Array<{ number: number; size_bytes: number; content_md5: string }> = [];
    for (let start = 0, number = 1; start < file.size; start += partSize, number++) {
      const end = Math.min(file.size, start + partSize);
      const part = md5.create();
      for (let offset = start; offset < end; offset += 1024 * 1024) {
        const bytes = new Uint8Array(await file.slice(offset, Math.min(end, offset + 1024 * 1024)).arrayBuffer());
        whole.update(bytes); part.update(bytes);
        self.postMessage({ type: 'progress', bytes: offset + bytes.length, total: file.size });
      }
      const digest = part.digest();
      parts.push({ number, size_bytes: end - start, content_md5: btoa(String.fromCharCode(...digest)) });
    }
    self.postMessage({ type: 'complete', sha256: bytesToHex(whole.digest()), parts });
  } catch (error) {
    self.postMessage({ type: 'error', message: error instanceof Error ? error.message : String(error) });
  }
};
