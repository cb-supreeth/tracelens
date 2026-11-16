package com.chargebee.tracelens.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import static com.chargebee.tracelens.tool.Json.*;

/**
 * Private, bounded, immutable local capture files. Locks coordinate Java writers.
 * Do not run Python and Java writers against the same store: flock and Java's
 * record locks are not interchangeable. Portable NIO cannot offer POSIX openat's
 * directory-race guarantee; use a trusted local owner-only storage hierarchy.
 * Unsupported Unix ownership/link/permission checks fail closed.
 */
public class CaptureStore {
    public final Path root;
    private static final Set<PosixFilePermission> DIR_MODE=PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> FILE_MODE=PosixFilePermissions.fromString("rw-------");
    private static final ConcurrentHashMap<Path,LockEntry> LOCKS=new ConcurrentHashMap<>();
    private static final class LockEntry { final ReentrantLock lock=new ReentrantLock(); int users; }
    public CaptureStore(Path root) { if(root==null)throw Captures.invalid("capture directory is required");this.root=expand(root).toAbsolutePath().normalize(); }
    static Path expand(Path path){String s=path.toString();return s.equals("~")?Paths.get(System.getProperty("user.home")):s.startsWith("~/")?Paths.get(System.getProperty("user.home"),s.substring(2)):path;}
    public static Path defaultDirectory(){return Paths.get(System.getProperty("user.home"),".tracelens","captures");}
    protected ToolException failure(String message){return new ToolException(409,message);}
    interface Operation<T>{T run() throws IOException;}
    protected <T> T locked(boolean create,Operation<T> operation) {return locked(create,create,operation);}
    protected <T> T locked(boolean create,boolean write,Operation<T> operation) {
        // Count waiters before they block, so releasing an owner cannot replace their shared lock.
        LockEntry entry=LOCKS.compute(root,(key,current)->{LockEntry value=current==null?new LockEntry():current;value.users++;return value;});
        entry.lock.lock();
        try {
            ensureDirectory(create);Path lock=root.resolve(".lock");
            if(write)try{Files.createFile(lock,PosixFilePermissions.asFileAttribute(FILE_MODE));}catch(FileAlreadyExistsException ignored){}
            if(!Files.exists(lock,LinkOption.NOFOLLOW_LINKS)){if(write)throw failure("capture lock disappeared");return operation.run();}
            Map<String,Object> before=privateFile(lock,false,false);
            try(FileChannel channel=FileChannel.open(lock,StandardOpenOption.READ,write?StandardOpenOption.WRITE:StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS);
                FileLock fileLock=channel.lock(0,Long.MAX_VALUE,!write)) {
                sameFile(lock,before);ensureDirectory(false);return operation.run();
            }
        }catch(NoSuchFileException e){throw new Missing();}
        catch(UnsupportedOperationException e){throw failure("private capture storage requires supported POSIX ownership, link and permission checks");}
        catch(IOException|SecurityException e){throw failure("private capture storage could not be safely accessed");}
        finally{
            entry.lock.unlock();
            LOCKS.compute(root,(key,current)->{
                if(current!=entry)throw new IllegalStateException("Capture lock ownership changed");
                return --entry.users==0?null:entry;
            });
        }
    }
    static final class Missing extends RuntimeException {}
    private long uid() throws IOException {
        // A file in the JVM's private temporary directory is not needed: the
        // process-owned home directory supplies the expected Unix principal.
        Object uid=Files.getAttribute(Paths.get(System.getProperty("user.home")),"unix:uid",LinkOption.NOFOLLOW_LINKS);
        UserPrincipal owner=Files.getOwner(Paths.get(System.getProperty("user.home")),LinkOption.NOFOLLOW_LINKS);
        String user=System.getProperty("user.name");
        UserPrincipal current=FileSystems.getDefault().getUserPrincipalLookupService().lookupPrincipalByName(user);
        if(!current.equals(owner))throw failure("cannot establish current owner for private capture storage");
        return ((Number)uid).longValue();
    }
    protected void ensureDirectory(boolean create) throws IOException {
        if(!Files.getFileStore(root.getRoot()).supportsFileAttributeView("unix"))throw failure("private capture storage requires a POSIX filesystem");
        Path current=root.getRoot();long currentUid=uid();
        for(Path component:root) {
            current=current.resolve(component);
            if(create)try{Files.createDirectory(current,PosixFilePermissions.asFileAttribute(DIR_MODE));}catch(FileAlreadyExistsException ignored){}
            BasicFileAttributes attrs=Files.readAttributes(current,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            if(!attrs.isDirectory()||attrs.isSymbolicLink())throw failure("capture directory components must be real directories, not links");
            Map<String,Object> security=Files.readAttributes(current,"unix:uid,mode",LinkOption.NOFOLLOW_LINKS);
            long owner=((Number)security.get("uid")).longValue();int mode=((Number)security.get("mode")).intValue();
            // NIO publication is path-based. Do not accept a hierarchy whose
            // components another unprivileged account can replace. Root-owned
            // sticky temporary directories retain per-entry owner protection.
            if(owner!=0&&owner!=currentUid||(mode&0022)!=0&&!(owner==0&&(mode&01000)!=0))
                throw failure("capture directory ancestors must have trusted ownership and must not be shared-writable");
        }
        Map<String,Object> attrs=Files.readAttributes(root,"unix:uid,mode",LinkOption.NOFOLLOW_LINKS);
        if(((Number)attrs.get("uid")).longValue()!=currentUid||(((Number)attrs.get("mode")).intValue()&0077)!=0)
            throw failure("capture directory must be owned by you and private (mode 0700)");
    }
    protected Map<String,Object> privateFile(Path path,boolean paired,boolean pending) throws IOException {
        Map<String,Object>a=Files.readAttributes(path,"unix:uid,mode,nlink,size,ino,dev",LinkOption.NOFOLLOW_LINKS);
        int mode=((Number)a.get("mode")).intValue();long links=((Number)a.get("nlink")).longValue();
        if((mode&0170000)!=0100000||((Number)a.get("uid")).longValue()!=uid()||(mode&0077)!=0
            ||!(links==1||(paired||pending)&&links==2))
            throw failure("capture files must be private, owned regular files without outside links");
        if(links==2&&!pending&&!pairedPending(a))throw failure("capture files must not have outside hardlinks");
        return a;
    }
    protected void sameFile(Path path,Map<String,Object> before) throws IOException {
        Map<String,Object>after=privateFile(path,true,false);for(String key:Arrays.asList("ino","dev","uid","mode","nlink"))
            if(!Objects.equals(before.get(key),after.get(key)))throw failure("capture file changed during access");
    }
    private boolean pairedPending(Map<String,Object>info)throws IOException{
        int count=0,examined=0;try(DirectoryStream<Path> entries=Files.newDirectoryStream(root)){
            for(Path p:entries)if(pendingName(p.getFileName().toString())) {
                if(++examined>Captures.MAX_CAPTURES)throw failure("too many interrupted capture files; review them manually");
                Map<String,Object>other=Files.readAttributes(p,"unix:mode,ino,dev",LinkOption.NOFOLLOW_LINKS);
                if((((Number)other.get("mode")).intValue()&0170000)==0100000&&Objects.equals(info.get("ino"),other.get("ino"))&&Objects.equals(info.get("dev"),other.get("dev")))count++;
            }
        }return count==1;
    }
    protected static boolean pendingName(String name){return name.startsWith(".pending-")&&name.substring(9).matches(Captures.UUID_RE);}
    protected List<String> names() throws IOException {
        List<String>names=new ArrayList<>();try(DirectoryStream<Path>entries=Files.newDirectoryStream(root)){for(Path p:entries){String name=p.getFileName().toString();
            if(name.endsWith(".json")&&name.substring(0,name.length()-5).matches(Captures.UUID_RE)){names.add(name);if(names.size()>20)throw failure("capture store exceeds the 20-capture limit; review files manually");}
        }}Collections.sort(names);return names;
    }
    protected long[] pendingUsage() throws IOException{
        long count=0,total=0;try(DirectoryStream<Path>entries=Files.newDirectoryStream(root)){for(Path p:entries)if(pendingName(p.getFileName().toString())){
            if(++count>20)throw failure("too many interrupted capture files; review them manually");Map<String,Object>a=privateFile(p,false,true);total+=((Number)a.get("size")).longValue();
        }}return new long[]{count,total};
    }
    protected void validate(JsonNode doc){Captures.validateCapture(doc);}
    protected String idField(){return "captureId";}
    protected static ObjectNode strictJson(byte[]bytes){
        try{JsonNode value=Json.parse(bytes);if(value==null||!value.isObject())throw Captures.invalid("JSON document must be an object");return (ObjectNode)value;}
        catch(IllegalArgumentException e){throw Captures.invalid("invalid JSON document");}
    }
    protected Record read(String name)throws IOException {
        Path path=root.resolve(name);Map<String,Object>before=privateFile(path,true,false);
        if(((Number)before.get("size")).longValue()>Captures.MAX_CAPTURE_BYTES)throw failure("capture file exceeds 5 MiB");
        ByteArrayOutputStream buffer=new ByteArrayOutputStream();
        try(FileChannel channel=FileChannel.open(path,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)){
            sameFile(path,before);ByteBuffer chunk=ByteBuffer.allocate(8192);
            while(channel.read(chunk)>=0){chunk.flip();int n=chunk.remaining();if(buffer.size()+n>Captures.MAX_CAPTURE_BYTES)throw failure("capture file exceeds 5 MiB");buffer.write(chunk.array(),0,n);chunk.clear();}
            sameFile(path,before);
        }
        ObjectNode doc;try{doc=strictJson(buffer.toByteArray());validate(doc);}catch(RuntimeException e){throw failure("invalid or unsupported saved evidence; no live data was substituted");}
        if(!(Captures.text(doc,idField())+".json").equals(name))throw failure("capture file identity does not match filename");return new Record(doc,buffer.size());
    }
    protected static final class Record{final ObjectNode doc;final int size;Record(ObjectNode doc,int size){this.doc=doc;this.size=size;}}
    protected int publish(JsonNode doc,boolean replacing)throws IOException {
        byte[]raw=Captures.bytes(doc);if(raw.length>Captures.MAX_CAPTURE_BYTES)throw new ToolException(413,"saved evidence exceeds the 5 MiB limit; nothing was saved");
        String name=Captures.text(doc,idField())+".json";List<String>names=names();long[]pending=pendingUsage();long total=pending[1];for(String n:names)total+=read(n).size;
        if(!replacing&&names.contains(name))throw failure("capture already exists; existing captures are never overwritten");
        if(!replacing&&names.size()+pending[0]>=20)throw failure("capture limit reached (20); delete or move unwanted evidence manually");
        if(replacing)total-=read(name).size;if(total+raw.length>Captures.MAX_STORE_BYTES)throw failure("capture storage limit reached (20 MiB); delete or move unwanted evidence manually");
        String temporary=".pending-"+UUID.randomUUID();Path pendingPath=root.resolve(temporary),target=root.resolve(name);
        ensureDirectory(false);Map<String,Object>directory=Files.readAttributes(root,"unix:ino,dev",LinkOption.NOFOLLOW_LINKS);
        try {
            try(FileChannel channel=FileChannel.open(pendingPath,EnumSet.of(StandardOpenOption.WRITE,StandardOpenOption.CREATE_NEW),PosixFilePermissions.asFileAttribute(FILE_MODE))) {
                ByteBuffer data=ByteBuffer.wrap(raw);while(data.hasRemaining())channel.write(data);channel.force(true);
            }
            ensureDirectory(false);Map<String,Object>fresh=Files.readAttributes(root,"unix:ino,dev",LinkOption.NOFOLLOW_LINKS);if(!directory.equals(fresh))throw failure("capture directory changed during publication");
            privateFile(pendingPath,false,false);
            if(replacing)Files.move(pendingPath,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            else{Files.createLink(target,pendingPath);Files.delete(pendingPath);}
            syncDirectory();return raw.length;
        }finally{Files.deleteIfExists(pendingPath);}
    }
    protected void syncDirectory()throws IOException {try(FileChannel directory=FileChannel.open(root,StandardOpenOption.READ)){directory.force(true);}}
    public ObjectNode save(JsonNode capture) {
        validate(capture);ObjectNode detached=strictJson(Captures.bytes(capture));
        return locked(true,()->{int size=publish(detached,false);return Captures.metadata(detached,size);});
    }
    public ObjectNode list() {
        List<JsonNode>sorted=new ArrayList<>();long[]pending={0,0};
        try{locked(false,()->{long[]usage=pendingUsage();pending[0]=usage[0];pending[1]=usage[1];for(String name:names()){Record record=read(name);sorted.add(Captures.metadata(record.doc,record.size));}return null;});}catch(Missing ignored){}
        sorted.sort(Comparator.comparing((JsonNode r)->Captures.text(r,"createdAt")).thenComparing(r->Captures.text(r,"captureId")).reversed());ArrayNode rows=arr();long total=pending[1];for(JsonNode row:sorted){rows.add(row);total+=row.path("sizeBytes").asLong();}
        return obj("captures",rows,"count",rows.size(),"limits",obj("maxCaptures",20,"maxCaptureBytes",Captures.MAX_CAPTURE_BYTES,"maxTotalBytes",Captures.MAX_STORE_BYTES),
            "totalBytes",total,"pendingFiles",pending[0],"pendingBytes",pending[1],"warnings",pending[0]>0?arr("Interrupted capture files count toward quota; review/move them manually. No automatic deletion is performed."):arr());
    }
    public ObjectNode get(String id){
        if(Captures.match(id,Captures.UUID_RE)==null)throw Captures.invalid("capture_id must be a canonical lowercase UUID, not a file path");
        try{return locked(false,()->read(id+".json").doc);}catch(Missing e){throw new ToolException(404,"capture not found");}
    }
}
