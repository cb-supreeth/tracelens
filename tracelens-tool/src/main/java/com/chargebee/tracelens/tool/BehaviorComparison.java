package com.chargebee.tracelens.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import static com.chargebee.tracelens.tool.Json.*;

/** Bounded correspondence between saved observations, never inferred execution order. */
final class BehaviorComparison {
    static final int MAX_ROWS=200,MAX_BYTES=48*1024,MAX_STATUSES=32;
    private BehaviorComparison(){}
    private static ObjectNode reference(JsonNode segment,String snapshot){ObjectNode result=Captures.select(segment,"service","segmentId","serviceInstanceId");result.put("snapshotId",snapshot);return result;}
    private static final class Side {
        long count;final Map<String,Long> statuses=new TreeMap<>();final Map<String,ObjectNode> evidence=new TreeMap<>();
        void add(JsonNode segment,String snapshot,JsonNode row,boolean method){
            long n=row.path("count").asLong(1);count+=n;ObjectNode ref=reference(segment,snapshot);
            if(method){ref.setAll(Captures.select(row,"className","methodName"));if(row.hasNonNull("methodDescriptor"))ref.set("methodDescriptor",row.get("methodDescriptor"));}
            String key=Captures.canonical(ref);ObjectNode existing=evidence.get(key);
            if(existing==null){ref.put("count",n);evidence.put(key,ref);}else existing.put("count",existing.path("count").asLong()+n);
            if(!method){String status=Captures.canonical(Captures.select(row,"status","httpStatus","errorType"));statuses.put(status,statuses.getOrDefault(status,0L)+n);}
        }
        ObjectNode json(){ArrayNode values=arr();for(Map.Entry<String,Long> entry:statuses.entrySet()){if(values.size()>=MAX_STATUSES)break;ObjectNode row=copy(parse(entry.getKey()));row.put("count",entry.getValue());values.add(row);}return obj("count",count,"statuses",values,"statusesOmitted",Math.max(0,statuses.size()-values.size()),"evidence",evidence.values());}
    }
    private static final class Group {
        final ObjectNode identity;final Side before=new Side(),after=new Side();Group(ObjectNode identity){this.identity=identity;}
    }
    private static Map<String,Group> groups(JsonNode before,JsonNode after,String beforeId,String afterId,boolean methods){
        Map<String,Group> groups=new TreeMap<>();
        for(int i=0;i<2;i++)for(JsonNode segment:(i==0?before:after).path("segments"))for(JsonNode row:segment.path(methods?"methods":"dependencies")){
            ObjectNode key=methods?Captures.select(row,"className","methodName"):Captures.select(row,"boundaryKind","system","direction","destination","operation","path","evidenceKind");key.set("service",segment.get("service"));
            if(methods&&row.hasNonNull("methodDescriptor"))key.set("methodDescriptor",row.get("methodDescriptor"));
            Group group=groups.computeIfAbsent(Captures.canonical(key),ignored->new Group(key));(i==0?group.before:group.after).add(segment,i==0?beforeId:afterId,row,methods);
        }
        return groups;
    }
    private static String disposition(long before,long after,boolean equal){return before==0?"after_only":after==0?"before_only":equal?"unchanged":"changed";}
    private static int priority(Group group,boolean methods){return group.after.count==0?0:group.before.count==0?1:group.before.count==group.after.count&&(methods||group.before.statuses.equals(group.after.statuses))?3:2;}
    private static String methodKey(JsonNode segment,JsonNode row,boolean parent){ObjectNode key=obj("service",segment.get("service"),"className",row.get(parent?"parentClass":"className"),"methodName",row.get(parent?"parentMethod":"methodName"));
        JsonNode descriptor=row.get(parent?"parentMethodDescriptor":"methodDescriptor");if(descriptor!=null&&!descriptor.isNull())key.set("methodDescriptor",descriptor);return Captures.canonical(key);}
    private static boolean mixedMethodPrecision(JsonNode before,JsonNode after){Map<String,Set<Boolean>> identities=new HashMap<>();
        for(JsonNode capture:Arrays.asList(before,after))for(JsonNode segment:capture.path("segments"))for(JsonNode method:segment.path("methods")){
            ObjectNode key=Captures.select(method,"className","methodName");key.set("service",segment.get("service"));Set<Boolean> kinds=identities.computeIfAbsent(Captures.canonical(key),ignored->new HashSet<>());kinds.add(method.hasNonNull("methodDescriptor"));if(kinds.size()>1)return true;
        }return false;}
    private static Map<String,String> methodParents(JsonNode before,JsonNode after){
        Map<String,String> parents=new TreeMap<>();Set<String> blocked=new HashSet<>();
        for(JsonNode capture:Arrays.asList(before,after))for(JsonNode segment:capture.path("segments")){
            Map<String,Long> counts=new HashMap<>();Map<String,Map<String,Long>> incoming=new HashMap<>();
            for(JsonNode method:segment.path("methods")){String child=methodKey(segment,method,false);counts.put(child,counts.getOrDefault(child,0L)+method.path("count").asLong());}
            for(JsonNode edge:segment.path("nesting")){String child=methodKey(segment,edge,false),parent=methodKey(segment,edge,true);Map<String,Long> edges=incoming.computeIfAbsent(child,key->new HashMap<>());edges.put(parent,edges.getOrDefault(parent,0L)+edge.path("count").asLong());}
            for(Map.Entry<String,Long> method:counts.entrySet()){
                String child=method.getKey();Map<String,Long> edges=incoming.get(child);
                if(edges==null||edges.size()!=1){blocked.add(child);continue;}
                Map.Entry<String,Long> edge=edges.entrySet().iterator().next();String parent=edge.getKey();
                // One aggregate edge is safe only when it places every observed invocation here.
                if(parent.equals(child)||!counts.containsKey(parent)||!edge.getValue().equals(method.getValue())){blocked.add(child);continue;}
                String previous=parents.putIfAbsent(child,parent);if(previous!=null&&!previous.equals(parent))blocked.add(child);
            }
        }
        for(String child:blocked)parents.remove(child);
        Set<String> done=new HashSet<>(),cyclic=new HashSet<>();
        for(String child:parents.keySet()){
            Set<String> path=new LinkedHashSet<>();String current=child;
            while(parents.containsKey(current)&&!done.contains(current)&&path.add(current))current=parents.get(current);
            if(path.contains(current)||cyclic.contains(current))cyclic.addAll(path);done.addAll(path);
        }
        for(String child:cyclic)parents.remove(child);return parents;
    }
    private static ObjectNode methodRow(Group group){ObjectNode row=copy(group.identity);row.put("disposition",disposition(group.before.count,group.after.count,group.before.count==group.after.count));row.put("beforeCount",group.before.count);row.put("afterCount",group.after.count);row.set("before",node(group.before.evidence.values()));row.set("after",node(group.after.evidence.values()));return row;}
    private static void appendMethods(ObjectNode result,JsonNode before,JsonNode after,String beforeId,String afterId){
        Map<String,Group> groups=groups(before,after,beforeId,afterId,true);Map<String,String> parents=methodParents(before,after),roots=new HashMap<>();Map<String,List<Group>> changes=new TreeMap<>();
        for(Map.Entry<String,Group> entry:groups.entrySet())if(entry.getValue().before.count!=entry.getValue().after.count){
            String root=entry.getKey();List<String> path=new ArrayList<>();while(parents.containsKey(root)&&!roots.containsKey(root)){path.add(root);root=parents.get(root);}root=roots.getOrDefault(root,root);for(String child:path)roots.put(child,root);
            changes.computeIfAbsent(root,key->new ArrayList<>()).add(entry.getValue());
        }
        List<Map.Entry<String,List<Group>>> ordered=new ArrayList<>(changes.entrySet());
        ordered.sort(Comparator.comparingInt(entry->entry.getValue().stream().mapToInt(group->priority(group,true)).min().orElse(3)));
        ArrayNode rows=(ArrayNode)result.get("methods");List<List<Group>> descendants=new ArrayList<>();
        for(Map.Entry<String,List<Group>> entry:ordered){
            if(rows.size()>=MAX_ROWS){omit(result,"methods");continue;}Group root=groups.get(entry.getKey());ObjectNode row=methodRow(root);List<Group> nested=new ArrayList<>(entry.getValue());nested.remove(root);nested.sort(Comparator.comparingInt(group->priority(group,true)));
            if(root.before.count==root.after.count)row.put("disposition","descendants_changed");row.set("nestedChanges",arr());row.put("nestedChangedCount",nested.size());row.put("nestedOmitted",nested.size());rows.add(row);descendants.add(nested);
        }
        int remaining=MAX_ROWS-rows.size();for(int i=0;i<rows.size();i++){
            ObjectNode row=(ObjectNode)rows.get(i);ArrayNode nested=(ArrayNode)row.get("nestedChanges");for(Group group:descendants.get(i)){if(remaining<=0)break;nested.add(methodRow(group));remaining--;}
            row.put("nestedOmitted",row.path("nestedChangedCount").asInt()-nested.size());if(row.path("nestedOmitted").asInt()>0)result.put("limited",true);
        }
    }
    private static boolean trimNestedMethod(ObjectNode result){ArrayNode rows=(ArrayNode)result.get("methods");for(int i=rows.size()-1;i>=0;i--){ObjectNode row=(ObjectNode)rows.get(i);ArrayNode nested=(ArrayNode)row.get("nestedChanges");if(!nested.isEmpty()){nested.remove(nested.size()-1);row.put("nestedOmitted",row.path("nestedOmitted").asInt()+1);result.put("limited",true);return true;}}return false;}
    private static Map<String,Long> outcomeCounts(ArrayNode values){Map<String,Long> result=new TreeMap<>();for(JsonNode row:values){String key=Captures.canonical(Captures.select(row,"status","httpStatus"));result.put(key,result.getOrDefault(key,0L)+1);}return result;}
    static ObjectNode build(JsonNode before,JsonNode after,String beforeId,String afterId){
        return build(before,after,beforeId,afterId,MAX_BYTES);
    }
    static ObjectNode build(JsonNode before,JsonNode after,String beforeId,String afterId,int maxBytes){
        return build(before,after,beforeId,afterId,maxBytes,Collections.emptyMap());
    }
    static ObjectNode build(JsonNode before,JsonNode after,String beforeId,String afterId,int maxBytes,Map<String,JsonNode> aliases){
        maxBytes=Math.min(maxBytes,MAX_BYTES);
        ObjectNode result=obj("schemaVersion",1,"completeness","observed-only","outcomes",arr(),"dependencies",arr(),"methods",arr(),"limited",false,"omitted",obj("outcomes",0,"dependencies",0,"methods",0),"omittedUnits",obj("methods","outermost-method-groups"),
            "warnings",arr("absence_does_not_prove_skip","correspondence_is_not_execution_order","source_and_runtime_build_not_verified"),"limits",obj("maxRowsPerCategory",MAX_ROWS,"maxMethodRowsIncludingNested",MAX_ROWS,"maxBytes",maxBytes));
        if(bool(before,"partial")||bool(after,"partial"))((ArrayNode)result.get("warnings")).add("partial_capture_evidence");
        if(mixedMethodPrecision(before,after))((ArrayNode)result.get("warnings")).add("mixed_method_identity_precision");
        Map<String,ObjectNode> outcomes=new TreeMap<>();boolean incompleteRequest=false;
        for(int i=0;i<2;i++)for(JsonNode segment:(i==0?before:after).path("segments")){
            ObjectNode key=Captures.select(segment.path("request"),"method","path");key.set("service",segment.get("service"));
            String identity=Captures.canonical(key);if(!key.hasNonNull("method")||!key.hasNonNull("path")){identity+="/"+i+"/"+Captures.canonical(reference(segment,i==0?beforeId:afterId));incompleteRequest=true;}
            ObjectNode group=outcomes.computeIfAbsent(identity,ignored->{ObjectNode value=copy(key);value.set("before",arr());value.set("after",arr());return value;});
            ObjectNode ref=reference(segment,i==0?beforeId:afterId);ref.setAll(Captures.select(segment.path("request"),"httpStatus","status","durationMs"));((ArrayNode)group.get(i==0?"before":"after")).add(ref);
        }
        if(incompleteRequest)((ArrayNode)result.get("warnings")).add("request_identity_incomplete");
        for(ObjectNode group:outcomes.values()){ArrayNode b=(ArrayNode)group.get("before"),a=(ArrayNode)group.get("after");group.put("disposition",disposition(b.size(),a.size(),outcomeCounts(b).equals(outcomeCounts(a))));((ArrayNode)result.get("outcomes")).add(group);}
        ArrayNode dependencies=(ArrayNode)result.get("dependencies");List<Group> values=new ArrayList<>(groups(before,after,beforeId,afterId,false).values());values.sort(Comparator.comparingInt(group->priority(group,false)));
        for(Group group:values){boolean equal=group.before.count==group.after.count&&group.before.statuses.equals(group.after.statuses);
            if(dependencies.size()>=MAX_ROWS){omit(result,"dependencies");continue;}ObjectNode row=copy(group.identity);row.put("disposition",disposition(group.before.count,group.after.count,equal));row.set("before",group.before.json());row.set("after",group.after.json());if(group.before.statuses.size()>MAX_STATUSES||group.after.statuses.size()>MAX_STATUSES)result.put("limited",true);dependencies.add(row);
            String name=Dependencies.aliasFor(aliases.get(Captures.text(row,"service")),Captures.text(row,"destination"));if(name!=null){row.put("destinationName",name);row.put("destinationNameSource","configured");}
        }
        appendMethods(result,before,after,beforeId,afterId);
        while(Captures.bytes(result).length>maxBytes){if(trimNestedMethod(result))continue;String category="methods";for(String candidate:new String[]{"dependencies","outcomes"})if(result.path(candidate).size()>result.path(category).size())category=candidate;
            ArrayNode rows=(ArrayNode)result.get(category);if(rows.isEmpty())throw Captures.invalid("Behavior comparison metadata exceeds byte limit");rows.remove(rows.size()-1);omit(result,category);
        }
        return result;
    }
    private static void omit(ObjectNode result,String category){ObjectNode omitted=(ObjectNode)result.get("omitted");omitted.put(category,omitted.path(category).asInt()+1);result.put("limited",true);}
}
