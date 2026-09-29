package com.iare.hackathon.commerce;

import java.io.*;
import java.util.*;
import javax.imageio.ImageIO;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class ProductImageController {
    private final ObjectProvider<JdbcTemplate> databases;
    public ProductImageController(ObjectProvider<JdbcTemplate> databases){this.databases=databases;}
    private JdbcTemplate database(){var jdbc=databases.getIfAvailable();if(jdbc==null)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Product image storage is unavailable.");return jdbc;}
    @PostMapping(value="/api/admin/product-images",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String,String> upload(@RequestPart("file") MultipartFile file)throws IOException{
        if(file.isEmpty()||file.getSize()>5*1024*1024)throw CommerceService.invalid("Choose a PNG or JPEG image no larger than 5 MB.");
        byte[] sanitized;
        try(var stream=ImageIO.createImageInputStream(file.getInputStream())){
            var readers=ImageIO.getImageReaders(stream);if(!readers.hasNext())throw CommerceService.invalid("Choose a valid PNG or JPEG image.");
            var reader=readers.next();try{
                if(!Set.of("png","jpeg","jpg").contains(reader.getFormatName().toLowerCase(Locale.ROOT)))throw CommerceService.invalid("Only PNG and JPEG images are supported.");
                reader.setInput(stream);if(reader.getWidth(0)>2048||reader.getHeight(0)>2048)throw CommerceService.invalid("Use an image no larger than 2048 × 2048 pixels.");
                var output=new ByteArrayOutputStream();ImageIO.write(reader.read(0),"png",output);sanitized=output.toByteArray();
            }finally{reader.dispose();}
        }
        if(sanitized.length>8*1024*1024)throw CommerceService.invalid("The decoded image is too large. Use a smaller image.");
        UUID id=UUID.randomUUID();database().update("INSERT INTO app_private.product_images(id,image_data) VALUES (?,?)",id,sanitized);
        return Map.of("imageUrl","/assets/product-images/"+id);
    }
    @GetMapping(value="/assets/product-images/{id}",produces=MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> image(@PathVariable UUID id){var bytes=database().query("SELECT image_data FROM app_private.product_images WHERE id=?",(r,n)->r.getBytes(1),id).stream().findFirst().orElseThrow(CommerceService::notFound);
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).header("X-Content-Type-Options","nosniff").body(bytes);}
}
