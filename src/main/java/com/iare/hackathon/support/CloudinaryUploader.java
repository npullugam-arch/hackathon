package com.iare.hackathon.support;

import com.cloudinary.Cloudinary;
import java.io.IOException;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Component
public class CloudinaryUploader {
    private final String cloudName,apiKey,apiSecret;
    public CloudinaryUploader(@Value("${cloudinary.cloud-name:}") String cloudName,@Value("${cloudinary.api-key:}") String apiKey,@Value("${cloudinary.api-secret:}") String apiSecret){this.cloudName=cloudName;this.apiKey=apiKey;this.apiSecret=apiSecret;}
    public String upload(MultipartFile file){
        if(file==null||file.isEmpty()||file.getSize()>5*1024*1024||!java.util.Set.of("image/png","image/jpeg","image/webp").contains(file.getContentType()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Screenshots must be PNG, JPEG or WebP images up to 5 MB.");
        if(cloudName.isBlank()||apiKey.isBlank()||apiSecret.isBlank())throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Screenshot uploads are not configured yet. You can submit the ticket without an image.");
        try{var cloudinary=new Cloudinary(Map.of("cloud_name",cloudName,"api_key",apiKey,"api_secret",apiSecret));var result=cloudinary.uploader().upload(file.getBytes(),Map.of("folder","hackathon/support"));return String.valueOf(result.get("secure_url"));}
        catch(IOException ex){throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Screenshot upload failed. Please try again.");}
    }

    private Cloudinary taskClient(){
        if(cloudName.isBlank()||apiKey.isBlank()||apiSecret.isBlank())throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Photo uploads are not configured yet. Please try again later.");
        return new Cloudinary(Map.of("cloud_name",cloudName,"api_key",apiKey,"api_secret",apiSecret,"secure",true,"timeout",20000));
    }
    public record TaskAsset(String url,String publicId) {}
    public TaskAsset uploadTaskPhoto(byte[] bytes,String publicId){
        try {
            var result=taskClient().uploader().upload(bytes,Map.of("public_id",publicId,"type","authenticated","resource_type","image","format","jpg","overwrite",false));
            Object url=result.get("secure_url"),id=result.get("public_id");
            if(!(url instanceof String text)||!text.startsWith("https://")||!publicId.equals(id))throw new IOException("Invalid upload response");
            return new TaskAsset(text,publicId);
        }catch(IOException ex){throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Photo upload failed. Please retry your submission.");}
    }
    public String taskPhotoUrl(String publicId){
        try{return taskClient().privateDownload(publicId,"jpg",Map.of("type","authenticated","resource_type","image","expires_at",java.time.Instant.now().plusSeconds(300).getEpochSecond(),"attachment",false));}
        catch(ResponseStatusException ex){throw ex;}catch(Exception ex){throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"The photo preview is temporarily unavailable.");}
    }
    public void deleteTaskPhoto(String publicId){
        try{taskClient().uploader().destroy(publicId,Map.of("type","authenticated","resource_type","image","invalidate",true));}
        catch(Exception ex){org.slf4j.LoggerFactory.getLogger(CloudinaryUploader.class).warn("An unreferenced private task upload could not be removed; retry cleanup for asset {}",publicId);}
    }
}
