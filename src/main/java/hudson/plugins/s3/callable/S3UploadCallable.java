package hudson.plugins.s3.callable;

import com.amazonaws.services.s3.model.CannedAccessControlList;
import hudson.FilePath;
import hudson.FilePath.FileCallable;
import hudson.plugins.s3.Destination;
import hudson.plugins.s3.FingerprintRecord;
import hudson.plugins.s3.MetadataPair;
import hudson.remoting.VirtualChannel;
import hudson.util.Secret;

import java.io.*;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

import com.amazonaws.regions.Region;
import com.amazonaws.regions.RegionUtils;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.s3.internal.Mimetypes;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.PutObjectRequest;
import com.amazonaws.services.s3.model.PutObjectResult;
import org.apache.commons.io.IOUtils;

public class S3UploadCallable extends AbstractS3Callable implements FileCallable<FingerprintRecord> {

    private static Map<String, String> convertOldMeta(List<MetadataPair> userMeta) {
        Map<String, String> result = new HashMap<String, String>();

        for (MetadataPair pair : userMeta) {
            result.put(pair.key, pair.value);
        }

        return result;
    }

    private static final long serialVersionUID = 1L;
    private final String bucketName;
    private final Destination dest;
    private final String storageClass;
    private final Map<String, String> userMetadata;
    private final String selregion;
    private final boolean produced;
    private final boolean useServerSideEncryption;
    private final boolean gzipFiles;
    private final boolean makePublic;

    @Deprecated
    public S3UploadCallable(boolean produced, String accessKey, Secret secretKey, boolean useRole, Destination dest, List<MetadataPair> userMetadata, String storageClass,
                            String selregion, boolean useServerSideEncryption) {
        this(produced, accessKey, secretKey, useRole, dest.bucketName, dest, convertOldMeta(userMetadata), storageClass, selregion, useServerSideEncryption, false, false);
    }

    public S3UploadCallable(boolean produced, String accessKey, Secret secretKey, boolean useRole, String bucketName, Destination dest, Map<String, String> userMetadata, String storageClass,
                            String selregion, boolean useServerSideEncryption, boolean gzipFiles, boolean makePublic) {
        super(accessKey, secretKey, useRole);
        this.bucketName = bucketName;
        this.dest = dest;
        this.storageClass = storageClass;
        this.userMetadata = userMetadata;
        this.selregion = selregion;
        this.produced = produced;
        this.useServerSideEncryption = useServerSideEncryption;
        this.gzipFiles = gzipFiles;
        this.makePublic = makePublic;
    }

    public ObjectMetadata buildMetadata(FilePath filePath) throws IOException, InterruptedException {
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentType(Mimetypes.getInstance().getMimetype(filePath.getName()));
        metadata.setContentLength(filePath.length());
        metadata.setLastModified(new Date(filePath.lastModified()));
        if ((storageClass != null) && !"".equals(storageClass)) {
            metadata.setHeader("x-amz-storage-class", storageClass);
        }
        if (useServerSideEncryption) {
            metadata.setServerSideEncryption(ObjectMetadata.AES_256_SERVER_SIDE_ENCRYPTION);
        }

        for (Map.Entry<String, String> entry : userMetadata.entrySet()) {
            String key = entry.getKey().toLowerCase();
            if (key.equals("cache-control")) {
                metadata.setCacheControl(entry.getValue());
            } else if (key.equals("expires")) {
                try {
                    Date expires = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z").parse(entry.getValue());
                    metadata.setHttpExpiresDate(expires);
                } catch (ParseException e) {
                    metadata.addUserMetadata(entry.getKey(), entry.getValue());
                }
            } else if (key.equals("content-encoding")) {
                metadata.setContentEncoding(entry.getValue());
            } else {
                metadata.addUserMetadata(entry.getKey(), entry.getValue());
            }
        }
        return metadata;
    }

    /**
     * Upload from slave directly
     */
    public FingerprintRecord invoke(File file, VirtualChannel channel) throws IOException, InterruptedException {
        return invoke(new FilePath(file));
    }

    /**
     * Stream from slave to master, then upload from master
     */
    public FingerprintRecord invoke(FilePath file) throws IOException, InterruptedException {
        setRegion();

        ObjectMetadata metadata = buildMetadata(file);

        InputStream inputStream = file.read();

        File localFile = null;

        if (gzipFiles) {
            localFile = File.createTempFile("s3plugin", ".bin");

            OutputStream outputStream = new FileOutputStream(localFile);

            outputStream = new GZIPOutputStream(outputStream, true);

            IOUtils.copy(inputStream, outputStream);
            outputStream.flush();
            outputStream.close();

            inputStream = new FileInputStream(localFile);
            metadata.setContentEncoding("gzip");
            metadata.setContentLength(localFile.length());
        }

        try {
            PutObjectRequest request = new PutObjectRequest(dest.bucketName, dest.objectName, inputStream, metadata)
                .withMetadata(metadata);

            if (makePublic) {
                request.withCannedAcl(CannedAccessControlList.PublicRead);
            }

            final PutObjectResult result = getClient().putObject(request);
            return new FingerprintRecord(produced, bucketName, file.getName(), result.getETag());
        } finally {
            if (localFile != null) {
                localFile.delete();
            }
        }
    }

    private void setRegion() {
        // In 0.7, selregion comes from Regions#name
        Region region = RegionUtils.getRegion(selregion);

        // In 0.6, selregion comes from Regions#valueOf
        if (region == null) {
            region = RegionUtils.getRegion(Regions.valueOf(selregion).getName());
        }

        getClient().setRegion(region);
    }
}
