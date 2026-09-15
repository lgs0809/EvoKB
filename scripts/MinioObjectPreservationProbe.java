import io.minio.*;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Read-only hashes of the existing application bucket, with the deployed native MinIO SDK. */
public final class MinioObjectPreservationProbe {
  public static void main(String[] args)throws Exception {
    var client=MinioClient.builder().endpoint(System.getenv("PROBE_MINIO_ENDPOINT"))
        .credentials(System.getenv("PROBE_MINIO_ACCESS"),System.getenv("PROBE_MINIO_SECRET")).build();
    String bucket=System.getenv("PROBE_MINIO_BUCKET");var hashes=new TreeMap<String,Object>();
    for(var result:client.listObjects(ListObjectsArgs.builder().bucket(bucket).recursive(true).build())) {
      var item=result.get();if(item.isDir())continue;
      var digest=MessageDigest.getInstance("SHA-256");long size=0;
      try(var stream=client.getObject(GetObjectArgs.builder().bucket(bucket).object(item.objectName()).build())) {
        byte[] buffer=new byte[65536];for(int n;(n=stream.read(buffer))!=-1;){digest.update(buffer,0,n);size+=n;}
      }
      if(size!=item.size())throw new IllegalStateException("object size changed while reading");
      hashes.put(item.objectName(),Map.of("size",size,"sha256",HexFormat.of().formatHex(digest.digest())));
    }
    new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(Path.of(args[0]).toFile(),hashes);
    client.close();
    System.out.println("READ_ONLY_OBJECTS_HASHED="+hashes.size());
  }
}
