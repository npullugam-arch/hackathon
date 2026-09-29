package com.iare.hackathon.phototask;
import java.awt.image.BufferedImage;
import java.io.*;
import java.security.*;
import java.util.*;
import javax.imageio.ImageIO;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
public final class PhotoValidation {
 private PhotoValidation(){}
 public record Image(byte[] bytes,String hash){}
 public static Image read(MultipartFile file){
  if(file==null||file.isEmpty()||file.getSize()>5*1024*1024||!Set.of("image/jpeg","image/png").contains(Objects.toString(file.getContentType(),"")))throw invalid();
  try {
   byte[] original;try(var stream=file.getInputStream()){original=stream.readNBytes(5*1024*1024+1);}if(original.length>5*1024*1024)throw invalid();
   try(var input=ImageIO.createImageInputStream(new ByteArrayInputStream(original))){
    var readers=ImageIO.getImageReaders(input);if(!readers.hasNext())throw invalid();var reader=readers.next();
    try{reader.setInput(input,true,true);String format=reader.getFormatName().toLowerCase(Locale.ROOT);
     if(!Set.of("jpeg","png").contains(format))throw invalid();int width=reader.getWidth(0),height=reader.getHeight(0);
     if(width<1||height<1||width>10000||height>10000||(long)width*height>16000000)throw invalid();
     var decoded=reader.read(0);var clean=new BufferedImage(width,height,BufferedImage.TYPE_INT_RGB);var graphics=clean.createGraphics();
     try{graphics.setColor(java.awt.Color.WHITE);graphics.fillRect(0,0,width,height);graphics.drawImage(decoded,0,0,null);}finally{graphics.dispose();decoded.flush();}
     var output=new ByteArrayOutputStream();if(!ImageIO.write(clean,"jpg",output))throw invalid();clean.flush();if(output.size()>8*1024*1024)throw invalid();
     return new Image(output.toByteArray(),HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(original)));
    }finally{reader.dispose();}
   }
  }catch(IOException|NoSuchAlgorithmException ex){throw invalid();}
 }
 private static ResponseStatusException invalid(){return new ResponseStatusException(HttpStatus.BAD_REQUEST,"Choose a valid JPEG or PNG photo up to 5 MB and 16 megapixels. Other files are not accepted.");}
}
