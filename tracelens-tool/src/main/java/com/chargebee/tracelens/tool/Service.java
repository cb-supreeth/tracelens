package com.chargebee.tracelens.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.io.IOException;
import java.util.*;

/** One operator-owned upstream; URLs and local roots never come from viewer arguments. */
public final class Service {
    public final String name,url,sourceRoot;
    public final ObjectNode dependencyAliases;
    public Service(String name,String url,String sourceRoot){this(name,url,sourceRoot,Json.obj());}
    public Service(String name,String url,String sourceRoot,ObjectNode aliases){
        if(name==null||!name.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}"))
            throw new IllegalArgumentException("service name must be 1–64 letters, digits, dots, underscores or hyphens");
        if(url==null||url.length()>2048||url.codePoints().anyMatch(c->Character.isWhitespace(c)||c<32))
            throw new IllegalArgumentException("invalid URL for "+name);
        URI uri;
        try{uri=new URI(url);}catch(Exception e){throw new IllegalArgumentException("invalid URL for "+name);}
        if(!Evidence.oneOf(uri.getScheme(),"http","https")||uri.getHost()==null||uri.getRawUserInfo()!=null||uri.getRawQuery()!=null||uri.getRawFragment()!=null)
            throw new IllegalArgumentException(name+": use an HTTP(S) base URL without credentials, query or fragment");
        if(uri.getPort() < -1||uri.getPort()>65535)throw new IllegalArgumentException("invalid URL port for "+name);
        if(sourceRoot!=null&&(sourceRoot.length()>4096||sourceRoot.indexOf('\0')>=0||!Paths.get(sourceRoot).isAbsolute()))
            throw new IllegalArgumentException(name+": sourceRoot must be an absolute local path");
        this.name=name;this.url=url.replaceAll("/+$","");this.sourceRoot=sourceRoot;this.dependencyAliases=Dependencies.validateAliases(aliases);
    }
    static String urlKey(String value){
        try{URI u=new URI(value);return u.getScheme().toLowerCase(Locale.ROOT)+"|"+u.getHost().toLowerCase(Locale.ROOT)+"|"+
                (u.getPort()<0?("https".equalsIgnoreCase(u.getScheme())?443:80):u.getPort())+"|"+u.getRawPath().replaceAll("/+$","");}
        catch(Exception e){throw new IllegalArgumentException("invalid upstream URL",e);}
    }
    public static List<Service> load(Path path) {
        try {
            if(Files.size(path)>65536)throw new IllegalArgumentException("services config exceeds 64 KiB");
            JsonNode doc=Json.parse(new String(Files.readAllBytes(path),StandardCharsets.UTF_8)), rows=doc.path("services");
            if(!rows.isArray()||rows.size()<1||rows.size()>32)throw new IllegalArgumentException("services config requires 1–32 services");
            List<Service> result=new ArrayList<>();Set<String>names=new HashSet<>(),urls=new HashSet<>();
            for(JsonNode row:rows){
                if(!row.isObject())throw new IllegalArgumentException("each service must be an object");
                String name=Json.text(row,"name"),url=Json.text(row,"url"),root=Json.text(row,"sourceRoot");
                if(name==null||!name.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}"))throw new IllegalArgumentException("service name must be 1–64 letters, digits, dots, underscores or hyphens");
                if(!names.add(name))throw new IllegalArgumentException("duplicate service name: "+name);
                if(url==null||url.length()>2048||url.codePoints().anyMatch(c->Character.isWhitespace(c)||c<32))throw new IllegalArgumentException("invalid URL for "+name);
                URI u;
                try{u=new URI(url);}catch(Exception e){throw new IllegalArgumentException("invalid URL for "+name);}
                if(!Evidence.oneOf(u.getScheme(),"http","https")||u.getHost()==null||u.getRawUserInfo()!=null||u.getRawQuery()!=null||u.getRawFragment()!=null)
                    throw new IllegalArgumentException(name+": use an HTTP(S) base URL without credentials, query or fragment");
                if(u.getPort() < -1||u.getPort()>65535)throw new IllegalArgumentException("invalid URL port for "+name);
                if(!urls.add(urlKey(url)))throw new IllegalArgumentException("duplicate upstream URL for "+name+"; configure each endpoint once");
                if(row.hasNonNull("sourceRoot")&&(root==null||root.length()>4096||root.indexOf('\0')>=0||!Paths.get(root).isAbsolute()))
                    throw new IllegalArgumentException(name+": sourceRoot must be an absolute local path");
                JsonNode aliases=row.has("dependencyAliases")?row.get("dependencyAliases"):Json.obj();
                result.add(new Service(name,url.replaceAll("/+$",""),root,Dependencies.validateAliases(aliases)));
            }
            return result;
        }catch(IOException e){throw new IllegalArgumentException("cannot read services config: "+e.getMessage(),e);}
    }
}
