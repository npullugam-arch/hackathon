package com.iare.hackathon.phototask;
import static com.iare.hackathon.phototask.PhotoTaskDtos.*;
import com.iare.hackathon.support.CloudinaryUploader;
import com.iare.hackathon.withdrawal.*;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
@Service
public class PhotoTaskService {
 private final ObjectProvider<PhotoTaskRepository> tasks;private final ObjectProvider<WithdrawalRepository> wallets;private final ObjectProvider<MachineRewardRepository> rewards;
 private final CloudinaryUploader media;private final WithdrawalEvents events;
 public PhotoTaskService(ObjectProvider<PhotoTaskRepository> tasks,ObjectProvider<WithdrawalRepository> wallets,ObjectProvider<MachineRewardRepository> rewards,CloudinaryUploader media,WithdrawalEvents events){this.tasks=tasks;this.wallets=wallets;this.rewards=rewards;this.media=media;this.events=events;}
 private <T>T required(ObjectProvider<T> provider){var value=provider.getIfAvailable();if(value==null)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Photo tasks are temporarily unavailable.");return value;}
 public State state(String uid){var repo=required(tasks);var wallet=required(wallets);var rewardRepo=required(rewards);return wallet.transaction(()->{long balance=wallet.lockUser(uid);UUID id=repo.machineId();boolean purchased=rewardRepo.hasPaidPurchase(uid);return new State(id,repo.machineName(id),repo.history(uid,id),rewardRepo.list(uid,"TAKE_PHOTO"),balance,purchased&&repo.active(id)&&repo.open(uid,id).isEmpty(),purchased,repo.active(id));});}
 private Task existing(PhotoTaskRepository repo,String uid,UUID machine,UUID request,String hash){
  var replay=repo.replay(uid,request);if(replay.isPresent()){
   var old=replay.get();if(!old.machineId().equals(machine)||!old.hash().equals(hash))throw conflict("This submission request was used for a different photo. Refresh and choose your photo again.");return old;
  }
  if(repo.open(uid,machine).isPresent())throw conflict("You already have a pending or completed photo task. Refresh to view its status.");
  if(!repo.active(machine))throw conflict("This photo task is not accepting submissions right now.");return null;
 }
 public Task submit(String uid,UUID request,MultipartFile file){
  if(request==null)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"A valid submission request ID is required.");
  var repo=required(tasks);var wallet=required(wallets);UUID machine=repo.machineId();var image=PhotoValidation.read(file);
    var rewardRepo=required(rewards);var replay=wallet.transaction(()->{wallet.lockUser(uid);if(!rewardRepo.hasPaidPurchase(uid))throw conflict("Purchase any product to unlock this task.");return existing(repo,uid,machine,request,image.hash());});if(replay!=null)return replay;
  UUID id=UUID.randomUUID();String publicId="hackathon/take-photo/"+id;
  var asset=media.uploadTaskPhoto(image.bytes(),publicId);
  try{return wallet.transaction(()->{
    wallet.lockUser(uid);if(!rewardRepo.hasPaidPurchase(uid))throw conflict("Purchase any product to unlock this task.");var old=existing(repo,uid,machine,request,image.hash());if(old!=null)return old;
   var task=repo.insert(id,machine,uid,request,asset.url(),asset.publicId(),image.hash());events.changedAfterCommit(uid);return task;
  });}finally{
   // Never delete on an uncertain database outcome. Only remove an upload proven to be unreferenced.
   try{if(!repo.referenced(asset.publicId()))media.deleteTaskPhoto(asset.publicId());}
   catch(org.springframework.dao.DataAccessException ex){org.slf4j.LoggerFactory.getLogger(PhotoTaskService.class).warn("Private photo cleanup deferred until database availability recovers; asset {}",asset.publicId());}
  }
 }
 public Page list(String status,int page){if(page<0||page>100000)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Choose a valid page.");if(status!=null&&!Set.of("PENDING","COMPLETED","REJECTED").contains(status))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Choose a valid task status.");return required(tasks).all(status,page);}
 public String image(UUID id,String owner){return media.taskPhotoUrl(required(tasks).one(id,owner,false).publicId());}
 public Task review(UUID id,Review input,String actor){
  if(input==null||!Set.of("COMPLETED","REJECTED").contains(Objects.toString(input.status(),""))||input.reason()!=null&&input.reason().length()>1000)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Choose a valid review status and a reason up to 1000 characters.");
  if(input.status().equals("REJECTED")&&(input.reason()==null||input.reason().isBlank()))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Enter a reason for rejecting this photo.");
  var repo=required(tasks);var wallet=required(wallets);var owner=repo.one(id,null,false).userId();
  return wallet.transaction(()->{
   wallet.lockUser(owner);var task=repo.one(id,null,true);
   if(!task.status().equals("PENDING")){if(task.status().equals(input.status()))return task;throw conflict("This task has already been reviewed. Refresh to see the final decision.");}
    var result=repo.review(task,input.status(),input.reason()==null?null:input.reason().trim(),actor);events.changedAfterCommit(owner);return result;
  });
 }
 static ResponseStatusException notFound(){return new ResponseStatusException(HttpStatus.NOT_FOUND,"This photo task is not available.");}
 private static ResponseStatusException conflict(String message){return new ResponseStatusException(HttpStatus.CONFLICT,message);}
}
