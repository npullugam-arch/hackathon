package com.iare.hackathon.phototask;
import static com.iare.hackathon.phototask.PhotoTaskDtos.*;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Repository;
@Repository
@ConditionalOnProperty(name="app.supabase.enabled",havingValue="true")
public class PhotoTaskRepository {
 private final JdbcTemplate jdbc;
 public PhotoTaskRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
 private static final String SELECT="SELECT t.*,COALESCE(t.ledger_id,c.ledger_id) AS reward_ledger,u.name AS user_name,u.email,m.name AS machine_name FROM app_private.photo_tasks t JOIN public.users u ON u.firebase_uid=t.user_id JOIN public.task_machines m ON m.id=t.machine_id LEFT JOIN app_private.machine_reward_claims c ON c.source_id=t.id AND c.user_id=t.user_id AND c.reward_type='TAKE_PHOTO'";
 private static final RowMapper<Task> MAP=(r,n)->new Task(r.getObject("id",UUID.class),r.getObject("machine_id",UUID.class),r.getString("user_id"),r.getString("user_name"),r.getString("email"),r.getString("machine_name"),r.getString("photo_url"),r.getString("cloudinary_public_id"),r.getString("photo_sha256"),r.getObject("request_id",UUID.class),r.getString("status"),r.getLong("reward_paise"),r.getTimestamp("submitted_at").toInstant(),r.getTimestamp("reviewed_at")==null?null:r.getTimestamp("reviewed_at").toInstant(),r.getString("reviewed_by"),r.getString("rejection_reason"),r.getObject("reward_ledger",UUID.class));
 public UUID machineId(){return jdbc.query("SELECT id FROM public.task_machines WHERE task_type='TAKE_PHOTO'",(r,n)->r.getObject(1,UUID.class)).stream().findFirst().orElseThrow(PhotoTaskService::notFound);}
 public String machineName(UUID id){return jdbc.queryForObject("SELECT name FROM public.task_machines WHERE id=?",String.class,id);}
 public boolean active(UUID machine){return jdbc.query("SELECT active FROM public.task_machines WHERE id=? AND task_type='TAKE_PHOTO'",(r,n)->r.getBoolean(1),machine).stream().findFirst().orElseThrow(PhotoTaskService::notFound);}
 public Optional<Task> replay(String uid,UUID request){return jdbc.query(SELECT+" WHERE t.user_id=? AND t.request_id=?",MAP,uid,request).stream().findFirst();}
 public Optional<Task> open(String uid,UUID machine){return jdbc.query(SELECT+" WHERE t.user_id=? AND t.machine_id=? AND t.status IN ('PENDING','COMPLETED')",MAP,uid,machine).stream().findFirst();}
 public List<Task> history(String uid,UUID machine){return jdbc.query(SELECT+" WHERE t.user_id=? AND t.machine_id=? ORDER BY t.submitted_at DESC,t.id DESC LIMIT 50",MAP,uid,machine);}
 public Task one(UUID id,String uid,boolean lock){return jdbc.query(SELECT+" WHERE t.id=?"+(uid==null?"":" AND t.user_id=?")+(lock?" FOR UPDATE OF t":""),MAP,uid==null?new Object[]{id}:new Object[]{id,uid}).stream().findFirst().orElseThrow(PhotoTaskService::notFound);}
 public Task insert(UUID id,UUID machine,String uid,UUID request,String url,String publicId,String hash){jdbc.update("INSERT INTO app_private.photo_tasks(id,machine_id,user_id,request_id,photo_url,cloudinary_public_id,photo_sha256) VALUES(?,?,?,?,?,?,?)",id,machine,uid,request,url,publicId,hash);return one(id,uid,false);}
 public boolean referenced(String publicId){return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM app_private.photo_tasks WHERE cloudinary_public_id=?)",Boolean.class,publicId));}
 public Task review(Task task,String status,String reason,String actor){
    UUID ledger=jdbc.query("SELECT id FROM app_private.winning_transactions WHERE user_id=? AND source_id=? AND kind='EARNING'",(r,n)->r.getObject(1,UUID.class),task.userId(),"take-photo:"+task.id()).stream().findFirst().orElse(null);
  jdbc.update("UPDATE app_private.photo_tasks SET status=?,rejection_reason=?,reviewed_by=?,reviewed_at=clock_timestamp(),ledger_id=? WHERE id=?",status,status.equals("REJECTED")?reason:null,actor,ledger,task.id());return one(task.id(),null,false);
 }
 public Page all(String status,int page){String where=status==null?"":" WHERE t.status=?";var args=new ArrayList<Object>();if(status!=null)args.add(status);
  long total=jdbc.queryForObject("SELECT count(*) FROM app_private.photo_tasks t"+where,Long.class,args.toArray());args.add(20);args.add(page*20);
  return new Page(jdbc.query(SELECT+where+" ORDER BY t.submitted_at DESC,t.id DESC LIMIT ? OFFSET ?",MAP,args.toArray()),page,20,total);
 }
}
