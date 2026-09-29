package com.iare.hackathon.support;

import static com.iare.hackathon.support.SupportDtos.*;
import jakarta.validation.Validator;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
public class SupportService {
    private final ObjectProvider<SupportRepository> repositories;private final CloudinaryUploader uploader;private final Validator validator;
    public SupportService(ObjectProvider<SupportRepository> repositories,CloudinaryUploader uploader,Validator validator){this.repositories=repositories;this.uploader=uploader;this.validator=validator;}
    private SupportRepository repository(){var value=repositories.getIfAvailable();if(value==null)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Support storage is temporarily unavailable.");return value;}
    public List<Ticket> tickets(String uid){return repository().user(uid);}
    public Ticket create(String uid,String title,String description,MultipartFile[] files){
        if(title==null||title.isBlank()||title.length()>160||description==null||description.isBlank()||description.length()>10000)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Enter a title and description within the allowed limits.");
        var urls=new ArrayList<String>();if(files!=null){if(files.length>5)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Attach no more than five screenshots.");for(var file:files)if(file!=null&&!file.isEmpty())urls.add(uploader.upload(file));}
        var repository=repository();return repository.one(repository.create(uid,title.trim(),description.trim(),urls),uid);
    }
    public List<Ticket> all(){return repository().all();}
    public Ticket detail(UUID id){return repository().one(id,null);}
    public Ticket update(UUID id,Status input){if(!validator.validate(input).isEmpty())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Choose a valid ticket status.");var repository=repository();repository.status(id,input);return repository.one(id,null);}
}