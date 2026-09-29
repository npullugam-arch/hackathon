package com.iare.hackathon.support;

import static com.iare.hackathon.support.SupportDtos.*;
import java.sql.Timestamp;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnProperty(name="app.supabase.enabled",havingValue="true")
public class SupportRepository {
    private final JdbcTemplate jdbc;
    public SupportRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
    private static final String SELECT="SELECT t.*,u.name AS user_name,COALESCE((SELECT json_agg(a.secure_url ORDER BY a.created_at) FROM app_private.support_ticket_attachments a WHERE a.ticket_id=t.id),'[]') AS attachments FROM app_private.support_tickets t JOIN public.users u ON u.firebase_uid=t.user_id";
    private Ticket map(java.sql.ResultSet r,int row) throws java.sql.SQLException {var json=r.getString("attachments");var list=new ArrayList<String>();if(json!=null){try{var node=tools.jackson.databind.json.JsonMapper.builder().build().readTree(json);if(node.isArray())for(var value:node)list.add(value.asString());}catch(Exception ignored){}}return new Ticket(r.getObject("id",UUID.class),r.getString("user_id"),r.getString("user_name"),r.getString("title"),r.getString("description"),r.getString("status"),r.getString("admin_remark"),r.getTimestamp("created_at").toInstant(),r.getTimestamp("updated_at").toInstant(),list);}
    public UUID create(String uid,String title,String description,List<String> urls){UUID id=UUID.randomUUID();jdbc.update("INSERT INTO app_private.support_tickets(id,user_id,title,description) VALUES(?,?,?,?)",id,uid,title,description);for(String url:urls)jdbc.update("INSERT INTO app_private.support_ticket_attachments(id,ticket_id,secure_url) VALUES(?,?,?)",UUID.randomUUID(),id,url);return id;}
    public Ticket one(UUID id,String uid){var args=new ArrayList<Object>();args.add(id);var sql=SELECT+" WHERE t.id=?";if(uid!=null){sql+=" AND t.user_id=?";args.add(uid);}return jdbc.query(sql,this::map,args.toArray()).stream().findFirst().orElseThrow(()->new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND,"Support ticket not found."));}
    public List<Ticket> user(String uid){return jdbc.query(SELECT+" WHERE t.user_id=? ORDER BY t.created_at DESC",this::map,uid);}
    public List<Ticket> all(){return jdbc.query(SELECT+" ORDER BY t.created_at DESC",this::map);}
    public void status(UUID id,Status input){jdbc.update("UPDATE app_private.support_tickets SET status=?,admin_remark=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",input.status(),input.remark(),id);}
}
