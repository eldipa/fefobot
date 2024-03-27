import java.nio.file.{Paths, Files}
import java.nio.charset.StandardCharsets

val projectName = System.getProperty("user.dir").split("/").last
importCode(inputPath = ".", projectName = projectName)


// mapping helpers
// these convert the Ast objects to an Issue object that keeps the information we want to show
case class Issue(filename: String, lineNumberStart: Option[Integer], lineNumberEnd: Option[Integer], code: Option[String])
def methodToIssue(ast: io.shiftleft.codepropertygraph.generated.nodes.AstNode): Issue = {
  val method = ast.asInstanceOf[Method]
  Issue(method.filename.toString, method.lineNumber, method.lineNumberEnd, None)
}

def literalToIssue(literal: io.shiftleft.codepropertygraph.generated.nodes.Literal): Issue = {
  Issue(literal.file.toArray.map(f => f.name).head, literal.lineNumber, None, Some(literal.code))
}

def paramToIssue(param: io.shiftleft.codepropertygraph.generated.nodes.MethodParameterIn): Issue = {
  Issue(param.file.toArray.map(f => f.name).head, param.lineNumber, None, None)
}

def functionCallToIssue(call: io.shiftleft.codepropertygraph.generated.nodes.Call): Issue = {
  Issue(call.file.toArray.map(f => f.name).head, call.lineNumber, None, None)
}

// Queries

// print stuff
val printing1 = cpg.identifier.name("cout").repeat(_.astParent)(_.until(_.isMethod)).toSet;
val printing2 = cpg.fieldAccess.code("std.*::.*cout").repeat(_.astParent)(_.until(_.isMethod)).toSet;

val printing = (printing1 ++ printing2).l;

// malloc, realloc, calloc
val xallocCCalls = cpg.call.name("malloc|realloc|calloc").method.l;

// "protocol"-like functions
val protocolFunctionNames = cpg.call.name("hton[sl]|ntoh[sl]").method.l;

// new/delete func calls
val newOp = cpg.call.name("<operator>.new").repeat(_.astParent)(_.until(_.isMethod)).l;
val delOp = cpg.call.name("<operator>.delete").repeat(_.astParent)(_.until(_.isMethod)).l;

// pointers
val byPtr = cpg.parameter.typeFullName(".*\\*.*").toSet;
val nativePtr = cpg.parameter.typeFullName("(bool|char|void)(\\[\\])?\\*.*").toSet;
val ptrs = (byPtr -- nativePtr).l;

// long strings
val longStrings = cpg.literal.typeFullName("char\\[\\d\\d+\\]").l;

// pass by value (vector, string, map, list of any type)
val passByValue = cpg.method.parameter.where(_.code(".*(:|\\s|^)(vector|string|map|list)[^a-zA-Z0-9_].*")).where(_.code("^[^&]*$")).repeat(_.astParent)(_.until(_.isMethod)).l;

// functions defined outside a class identifier (maybe static or global)
val outsideClassFunctions = cpg.method.whereNot(_.signature(".*\\..*")).whereNot(_.signature(".* main\\s*\\(.*")).whereNot(_.code("(<empty>|<global>)")).l;

// buffer allocs
val bufferDefinitions = cpg.local.typeFullName(".*\\[.*").repeat(_.astParent)(_.until(_.isMethod)).l;

// long functions (more than 50 lines)
val longFunctions = ({
  cpg.method.internal.filter(_.numberOfLines > 50).nameNot("<global>")
}).l;

// nested loops

val nestedLoops = ({
  cpg.method.internal
    .filter(
      _.ast.isControlStructure
        .controlStructureType("(FOR|DO|WHILE)")
        .size > 3
    )
    .nameNot("<global>")
}).l;



val output = Map(
  "print" -> printing.map(methodToIssue),
  "x_alloc" -> xallocCCalls.map(methodToIssue),
  "protocol_functions" -> protocolFunctionNames.map(methodToIssue),
  "new" -> newOp.map(methodToIssue),
  "del" -> delOp.map(methodToIssue),
  "raw_pointers" -> ptrs.map(paramToIssue),
  "long_strings" -> longStrings.map(literalToIssue),
  "copies" -> passByValue.map(methodToIssue),
  "outside_class" -> outsideClassFunctions.map(methodToIssue),
  "buffer" -> bufferDefinitions.map(methodToIssue),
  "long_functions" -> longFunctions.map(methodToIssue),
  "nested_loops" -> nestedLoops.map(methodToIssue)
).toJson

Files.write(Paths.get("issues-" + projectName + ".json"), output.getBytes(StandardCharsets.UTF_8))

exit