package com.iare.hackathon.phototask;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.*;
public final class PhotoTaskDtos {
 private PhotoTaskDtos(){}
 public record Task(UUID id,UUID machineId,String userId,String userName,String email,String machineName,
  @JsonIgnore String photoUrl,@JsonIgnore String publicId,@JsonIgnore String hash,UUID requestId,
  String status,long rewardPaise,Instant submittedAt,Instant reviewedAt,String reviewedBy,String rejectionReason,UUID ledgerId){}
 public record Review(@NotBlank @Pattern(regexp="COMPLETED|REJECTED") String status,@Size(max=1000) String reason){}
 public record State(UUID machineId,String machineName,List<Task> tasks,List<MachineRewardDtos.Reward> rewards,long availableWinningPaise,boolean eligible,boolean purchased,boolean machineActive){}
 public record Page(List<Task> items,int page,int size,long total){}
}
