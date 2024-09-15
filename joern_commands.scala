import java.nio.file.{Paths, Files}
import java.nio.charset.StandardCharsets

val projectName = System.getProperty("user.dir").split("/").last
importCode(inputPath = ".", projectName = projectName)

// The fefobot.sh should had injected some lines of code at the begin of each file (.cpp and .h)
// This additional lines are only for us and not visible by the student nor github so when joern computes
// the lines of each method, we need to substract N from the line numbers as if those injected lines
// never existed.
// This N comes from outside in the sourceCodeOffset environment variable
val sourceCodeOffset = System.getenv().getOrDefault("sourceCodeOffset", "0").toInt

// mapping helpers
// these convert the Ast objects to an Issue object that keeps the information we want to show
case class Issue(filename: String, lineNumberStart: Option[Integer], lineNumberEnd: Option[Integer], code: Option[String])
def methodToIssue(ast: io.shiftleft.codepropertygraph.generated.nodes.AstNode): Issue = {
  val method = ast.asInstanceOf[Method]
  Issue(method.filename.toString, method.lineNumber.map(_ - sourceCodeOffset), method.lineNumberEnd.map(_ - sourceCodeOffset), None)
}

def literalToIssue(literal: io.shiftleft.codepropertygraph.generated.nodes.Literal): Issue = {
  Issue(literal.file.toArray.map(f => f.name).head, literal.lineNumber.map(_ - sourceCodeOffset), None, Some(literal.code))
}

def paramToIssue(param: io.shiftleft.codepropertygraph.generated.nodes.MethodParameterIn): Issue = {
  Issue(param.file.toArray.map(f => f.name).head, param.lineNumber.map(_ - sourceCodeOffset), None, None)
}

def functionCallToIssue(call: io.shiftleft.codepropertygraph.generated.nodes.Call): Issue = {
  Issue(call.file.toArray.map(f => f.name).head, call.lineNumber.map(_ - sourceCodeOffset), None, None)
}

// Queries
//
// Convention:
//  - the name of queries that not represent issues in the code are prefixed with underscore
//    (eg _coutCalls are Calls to cout, it is not a real issue but an auxiliar query)
//  - *always* call .l, .toList or .toSet. Joern returns Traversable objects (like Iterators) that
//    once you iterate over, you cannot do it again (they get exhausted) an nobody will warn you!
//  - if you want to keep dealing with Traversable|Iterators, postfix with Iter
//  - if you are dealing with Sets, no additional postfixed is assumed (_coutCalls is a Set of Calls)
//    but if you are working with List or Seq, add that to the name (_coutCallsList is a List of Calls)


// Detect [std::]cout calls used for logic and not error printing ----------------------------
//
// The cout or std::cout calls
val _coutCalls = (cpg.call.code("cout[ <].*").toSet | cpg.call.code("std[ ]*::[ ]*cout[ <].*").toSet);

// From each cout call search for the first inner "if" control structure that is of the form "if (fefobotCatch())"
// These "if" are actually the "catch" statement that were patched by fefobot.sh
// We want to *ignore* these cout calls because they are likely to be error printing so we issue codeNot(...)
// and from there we get the inner method
// Note: when dealing with ast[Parent|is*] methods we are dealing with AstNode objects. From there
// we need to go back to Methods, Calls and those objects with
//   <astIterable>.collect(astNode => astNode.asInstanceOf[Method])
val _nonErrorCoutInMethods = _coutCalls.repeat(_.astParent)(_.until(_.isControlStructure)).codeNot(raw"if \(fefobotCatch\(\)\)").repeat(_.astParent)(_.until(_.isMethod)).collect(astNode => astNode.asInstanceOf[Method]).toSet;

// Detect [std::]cin or [std::]getline calls
val _cinInMethods = (cpg.call.code("cin[ >].*").toSet | cpg.call.code("std[ ]*::[ ]*cin[ >].*").toSet).method.toSet;
val _getlineInMethods = (cpg.call.code(raw"getline[ ]*\(.*").toSet | cpg.call.code(raw"std[ ]*::[ ]*getline[ ]*\(.*").toSet).method.toSet;

// Any method that we belive contains logic of the app
val _appLogicMethods = (_nonErrorCoutInMethods | _cinInMethods | _getlineInMethods);

// Detect socket/protocol-like calls
val _protocolMethods = cpg.call.name("hton[sl]|ntoh[sl]").method.toSet;
val _socketMethods = cpg.call.name("sendall|sendsome|recvall|recvall").method.toSet;
val _rawSocketMethods = cpg.call.name("send|recv").method.toSet;

// Any method that we belive contains protocol/socket logic
val _protocolSocketLogicMethods = (_protocolMethods | _socketMethods | _rawSocketMethods);

// Detect the filenames where there are methods that mix logic with the protocol
// The mix may not happen within the same method but in two different methods of the same file
val _mixingLogicFilenames = (_appLogicMethods.filename.toSet & _protocolSocketLogicMethods.filename.toSet);

// Now filter the methods that call cout/cin/... and the ones that do protocol/socket stuff.
// A single method may not do both but it will belong to a filename that has both.
// The hypothesis is that if even 2 methods are in the same filename, they belong to the same "unit" (or class)
// and they should not be mixing logic and protocol/socket.
val _appOrProtocolSocketLogicMethods = (_appLogicMethods | _protocolSocketLogicMethods);
val _mixingLogicMethods = _appOrProtocolSocketLogicMethods.filter(meth => _mixingLogicFilenames.contains(meth.filename)).toSet


// Very-likely incorrect use of raw send/recv functions
val maybeMisuseSendRecvMethods = _rawSocketMethods;

// Check printf scanf  str[n]?cmp str[n]?cpy memcmp
val cFuncCallMethods = cpg.call.name("str[n]?cmp|str[n]cpy|memcmp|printf|scanf").method.toSet;

// Calls to malloc, realloc, calloc
val xallocCallMethods = cpg.call.name("malloc|realloc|calloc|free").method.toSet;

// "Local" is a bad name but it is how Joern calls any variable, being local or not.
// In this case we search all the locals where from each local we have a method with the special
// name "<global>" which, obviosly means the locals not locals!
val globalVarsLocals = cpg.local.where(locals => locals.method.name(raw"\<global\>")).toSet;


// new/delete func calls
val newOp = cpg.call.name("<operator>.new").repeat(_.astParent)(_.until(_.isMethod)).l;
val delOp = cpg.call.name("<operator>.delete").repeat(_.astParent)(_.until(_.isMethod)).l;

// Pass by pointer. For primitive types like char or void, it is ok, for the rest
// it may indicate an incorrect use of pointers
// This is going to have false positives when we deal with polymorphism
val _passByPtrParameters = cpg.parameter.typeFullName(raw".*\*.*").toSet;
val _passNativeByParamenters = cpg.parameter.typeFullName(raw"(bool|char|void)(\[\])?\*.*").toSet;
val maybeUnneededPassByPtrParameters = (_passByPtrParameters -- _passNativeByParamenters);

// Pass by value non-trivially copiable (and potentially large) object like vector, string, map and list of any type
val _passByValueParameters = cpg.method.parameter.code(raw".*(:|\s|^)(vector|string|map|list)[^a-zA-Z0-9_].*").code("^[^&]*$").toSet;


// long strings
val longStrings = cpg.literal.typeFullName("char\\[\\d\\d+\\]").l;

// Functions defined outside a class identifier (maybe static or global)
//
// The _.signature(raw".*\..*") matches signature of the form Class.Method,
// the _.signature(raw".* main\s*\(.*") matches the 'main()' functions
// and the _.code("(<empty>|<global>)") are functions or methods with no source code
// which are likely to be from the system/OS/stdlib
//
// None of those represents student's real static/global functions so we search
// for any method that does not match any of those.
val globalFuncMethods = cpg.method.signatureNot(raw".*\..*").signatureNot(raw".* main\s*\(.*").codeNot("(<empty>|<global>)")).toSet


// Local/Stack buffer allocations -------------------------------------------------------------------------
//
// The following catches buffers of all sizes except 2 and 3. We expect to see
// buffers of 2 or 3 elements but nor more or less.
// Things like char[1] or char[64] is likely to be incorrect.
// NOTE: querying over typeFullName is more robust because it pre-process the string
// removing syntactic-valid-but-regex-annyoing whitespace
// NOTE: typeFullName also resolves the 'defines' so if we have 'char buf[SIZE]', typeFullName should
// be 'char[64]' given  '#define SIZE 64'. NICE!
val stackBufferAllocatedMethods = cpg.local.typeFullName(raw".*\[[ ]*\d+[ ]*\].*").typeFullNameNot(raw".*\[[ ]*[23][ ]*\].*").method.toSet;


// -------------------------------------------------------------------------

// Long functions (more than 35 lines)
val longFunctions = ({
  cpg.method.internal.filter(_.numberOfLines > 35).nameNot("<global>")
}).toSet;


// Methods with nested loops
val nestedLoopsMethods = ({
  cpg.method.internal
    .filter(
      _.ast.isControlStructure
        .controlStructureType("(FOR|DO|WHILE)")
        .size > 3
    )
    .nameNot("<global>")
}).toSet;



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