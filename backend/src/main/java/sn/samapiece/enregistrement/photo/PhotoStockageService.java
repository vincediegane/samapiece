package sn.samapiece.enregistrement.photo;

import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import java.io.ByteArrayInputStream;
import org.springframework.stereotype.Service;

@Service
public class PhotoStockageService {

    private static final String CONTENT_TYPE_STOCKAGE = "application/octet-stream";

    private final MinioClient minioClient;
    private final String bucket;

    public PhotoStockageService(PhotoMinioProperties proprietes) {
        this.minioClient = MinioClient.builder()
                .endpoint(proprietes.getEndpoint())
                .credentials(proprietes.getAccessKey(), proprietes.getSecretKey())
                .build();
        this.bucket = proprietes.getBucketPhotos();
    }

    public void televerser(String cleObjet, byte[] octetsChiffres) {
        try {
            assurerBucket();
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(cleObjet)
                    .stream(new ByteArrayInputStream(octetsChiffres), octetsChiffres.length, -1)
                    .contentType(CONTENT_TYPE_STOCKAGE)
                    .build());
        } catch (Exception e) {
            throw new PhotoStockageException("Echec du televersement de la photo vers MinIO.", e);
        }
    }

    public byte[] telecharger(String cleObjet) {
        try (GetObjectResponse reponse = minioClient.getObject(
                GetObjectArgs.builder().bucket(bucket).object(cleObjet).build())) {
            return reponse.readAllBytes();
        } catch (Exception e) {
            throw new PhotoStockageException("Echec du telechargement de la photo depuis MinIO.", e);
        }
    }

    private void assurerBucket() throws Exception {
        boolean existe = minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
        if (!existe) {
            minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
        }
    }
}
