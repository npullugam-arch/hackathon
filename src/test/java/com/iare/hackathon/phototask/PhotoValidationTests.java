package com.iare.hackathon.phototask;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
class PhotoValidationTests {
 @Test void validImageIsDecodedAndReencodedWithoutTrailingPayload()throws Exception{
  var original=PhotoTaskServiceTests.photo();var image=PhotoValidation.read(original);assertEquals(64,image.hash().length());assertNotNull(javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(image.bytes())));assertEquals(0xff,image.bytes()[0]&0xff);
 }
 @Test void emptyOversizedSvgAndDisguisedFilesAreRejected(){
  for(var file:new MockMultipartFile[]{new MockMultipartFile("photo",new byte[0]),new MockMultipartFile("photo","big.jpg","image/jpeg",new byte[5*1024*1024+1]),new MockMultipartFile("photo","x.svg","image/svg+xml","<svg/>".getBytes()),new MockMultipartFile("photo","x.jpg","image/jpeg","<script>alert(1)</script>".getBytes())})assertThrows(ResponseStatusException.class,()->PhotoValidation.read(file));
 }
 @Test void cloudPreviewIsSignedAndTimeLimitedWithoutNetwork(){
  var cloud=new com.iare.hackathon.support.CloudinaryUploader("test-cloud","public-key","server-only-secret");var url=cloud.taskPhotoUrl("hackathon/take-photo/test");assertTrue(url.startsWith("https://"));assertTrue(url.contains("expires_at="));assertTrue(url.contains("signature="));assertFalse(url.contains("server-only-secret"));
 }
}
