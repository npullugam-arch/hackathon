package com.iare.hackathon.withdrawal;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.security.SecureRandom;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Persistent server-owned encryption material. Users never supply bank credentials or keys. */
@Component
public class BankKeyStore {
    private final Path directory;
    private final ObjectProvider<JdbcTemplate> databases;
    private final String legacyKey;
    private byte[] cached;
    public BankKeyStore(Environment environment,ObjectProvider<JdbcTemplate> databases){
        this.databases=databases;
        directory=Path.of(environment.getProperty("app.security.key-directory",Path.of(System.getProperty("user.home"),".hackathon","security").toString())).toAbsolutePath().normalize();
        // Compatibility only: keep previously encrypted accounts readable during upgrades.
        legacyKey=environment.getProperty("app.withdrawal.bank-encryption-key",environment.getProperty("WITHDRAWAL_BANK_ENCRYPTION_KEY",""));
    }
    public synchronized byte[] key(){
        if(cached!=null)return cached.clone();
        try{
            if(!legacyKey.isBlank()){var bytes=Base64.getDecoder().decode(legacyKey);if(bytes.length!=32)throw new IllegalArgumentException();cached=bytes;return cached.clone();}
            Files.createDirectories(directory);
            if(Files.isSymbolicLink(directory))throw new IOException();
            restrict(directory,true);
            Path lock=directory.resolve("bank-key.lock"),file=directory.resolve("bank-key.bin");
            if(Files.isSymbolicLink(lock)||Files.isSymbolicLink(file))throw new IOException();
            try(var channel=FileChannel.open(lock,StandardOpenOption.CREATE,StandardOpenOption.WRITE);var ignored=channel.lock()){
                restrict(lock,false);
                if(Files.exists(file,LinkOption.NOFOLLOW_LINKS)){
                    restrict(file,false);var bytes=Files.readAllBytes(file);if(bytes.length!=32)throw new IOException();cached=bytes;
                }else{
                    var jdbc=databases.getIfAvailable();
                    if(jdbc==null)throw new IOException();
                    if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM app_private.bank_accounts)",Boolean.class)))
                        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"The original server encryption key must be restored before existing bank accounts can be used.");
                    byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);
                    try(var output=FileChannel.open(file,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)){
                        restrict(file,false);var buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining())output.write(buffer);output.force(true);
                    }
                    cached=bytes;
                }
            }
            return cached.clone();
        }catch(IOException|IllegalArgumentException ex){throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Secure bank storage is unavailable. Check the server's private key storage permissions.");}
    }
    private static void restrict(Path path,boolean directory)throws IOException{
        var posix=Files.getFileAttributeView(path,PosixFileAttributeView.class,LinkOption.NOFOLLOW_LINKS);
        if(posix!=null){posix.setPermissions(PosixFilePermissions.fromString(directory?"rwx------":"rw-------"));return;}
        var acl=Files.getFileAttributeView(path,AclFileAttributeView.class,LinkOption.NOFOLLOW_LINKS);
        if(acl==null)throw new IOException("Private file permissions are unsupported");
        var owner=Files.getOwner(path,LinkOption.NOFOLLOW_LINKS);
        acl.setAcl(List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(owner).setPermissions(EnumSet.allOf(AclEntryPermission.class)).build()));
    }
}
