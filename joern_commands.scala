importCode(inputPath = ".", projectName = System.getProperty("user.dir").split("/").last)

// print stuff
val printing1 = cpg.identifier.name("cout").repeat(_.astParent)(_.until(_.isMethod)).toSet;
val printing2 = cpg.fieldAccess.code("std.*::.*cout").repeat(_.astParent)(_.until(_.isMethod)).toSet;

val printing = (printing1 ++ printing2).l;

// malloc, realloc, calloc
val xallocCCalls = cpg.call.name("malloc|realloc|calloc").method.l;

// "protocol" functions
val protocolFunctionNames = cpg.call.name("hton[sl]|ntoh[sl]").method.fullName.l;

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

// atoi calls
val atoiCalls = cpg.call.name("atoi").astParent.l;

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


val res = List(printing,
  xallocCCalls,
  protocolFunctionNames,
  newOp,
  delOp,
  ptrs,
  longStrings,
  passByValue,
  atoiCalls,
  outsideClassFunctions,
  bufferDefinitions,
  longFunctions,
  nestedLoops
).flatMap(identity).toJson

exit