import scala.io.Source
import java.nio.file.{Paths, Files}
import java.nio.charset.StandardCharsets
import scala.sys.process._

// Let Joern try to exec this (if the env var is not given, the default (a literal string) should fail anyways)
val joernExternalHelperBin = System.getenv().getOrDefault("JOERN_EXTERNAL_HELPER", "JOERN_EXTERNAL_HELPER")

val projectName = System.getProperty("user.dir").split("/").last
importCode.cpp(inputPath = ".", projectName = projectName)

// Return the content of the file between those 2 lines
def readLinesBetween(filename: String, startLine: Int, endLine: Int): String = {
  require(startLine > 0, "startLine must be greater than 0")
  require(endLine >= startLine, "endLine must be greater than or equal to startLine")

  val source = Source.fromFile(filename)
  try {
    source.getLines()
      .slice(startLine - 1, endLine)
      .mkString("\n")
  } finally {
    source.close()
  }
}

def readSourceCodeOfLocal(local: io.shiftleft.codepropertygraph.generated.nodes.Local): String = {
  readLinesBetween(local.method.filename.head, local.lineNumber.get.toInt, local.lineNumber.get.toInt);
}

def readSourceCodeOfMethod(method: io.shiftleft.codepropertygraph.generated.nodes.Method): String = {
  readLinesBetween(method.filename, method.lineNumber.get.toInt, method.lineNumberEnd.get.toInt);
}

//val clientMessages = System.getenv().getOrDefault("sourceCodeOffset", "0").toInt

// mapping helpers
val extractInfoFromCall = (call: io.shiftleft.codepropertygraph.generated.nodes.Call) => {
    Map(
      "id" -> call.id,
      "lineNumber" -> call.lineNumber,
      "code" -> call.code,
      "name" -> call.name
    );
}

val extractInfoFromLocal = (local: io.shiftleft.codepropertygraph.generated.nodes.Local) => {
    Map(
      "id" -> local.id,
      "lineNumber" -> local.lineNumber,
      "code" -> local.code,
      "name" -> local.name,
      "typeFullName" -> local.typeFullName
    );
}

val extractInfoFromParameter = (parameter: io.shiftleft.codepropertygraph.generated.nodes.MethodParameterIn) => {
    Map(
      "id" -> parameter.id,
      "lineNumber" -> parameter.lineNumber,
      "code" -> parameter.code,
      "name" -> parameter.name,
      "typeFullName" -> parameter.typeFullName
    );
}

val extractInfoFromMethod = (method: io.shiftleft.codepropertygraph.generated.nodes.Method) => {
    Map(
      "id" -> method.id,
      "lineNumber" -> method.lineNumber,
      "lineNumberEnd" -> method.lineNumberEnd,
      "code" -> method.code,
      "name" -> method.name,
      "filename" -> method.filename,
      "fullName" -> method.fullName,
      "signature" -> method.signature
    );
}


// Variable where we are going to collect the results of the queries to be processed
// later by markdown_issue_builder
val issuesDetected = scala.collection.mutable.Map[String, String]()

// Queries
//
// Convention:
//  - the name of queries that don't represent issues in the code are prefixed with underscore
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
// and from there we get the inner method.
//
// The where() method will drop any call that has an empty Iterator as return.
// For the cout calls *without* being in an "if" control, we need to provide a fake Iterator
// (Iterator(42)) otherwise those will be missing those cout calls.
//
// Note: we used to deal with ast[Parent|is*] methods so used to work with AstNode objects.
// To cast them back to Method we used to do it with
//   <astIterable>.collect(astNode => astNode.asInstanceOf[Method])
// Now, we are encapsulating the AstNode and ast* methods in the where() condition so it not longer
// is a problem.
val _nonErrorCoutCalls = (
    _coutCalls.where {
      call =>
        val ctrlStructs = call.repeat(_.astParent)(_.until(_.isControlStructure));
        if (ctrlStructs.nonEmpty) ctrlStructs.codeNot(raw"if \(fefobotCatch\(\)\)") else Iterator(42);
    }).toSet;


// Detect [std::]cin or [std::]getline calls
val _cinCalls = (cpg.call.code("cin[ >].*").toSet | cpg.call.code("std[ ]*::[ ]*cin[ >].*").toSet);
val _getlineCalls = (cpg.call.code(raw"getline[ ]*\(.*").toSet | cpg.call.code(raw"std[ ]*::[ ]*getline[ ]*\(.*").toSet);

// Any call that we belive is logic of the app
val _appLogicCalls = _nonErrorCoutCalls | _cinCalls | _getlineCalls;
val _appLogicMethods = _appLogicCalls.method.toSet;

// Detect socket/protocol-like calls
// Note: callIn lists the calls to the given method
val _protocolCalls = cpg.call.name("hton[sl]|ntoh[sl]").toSet;
val byteHandlingCalls = (cpg.method.name("<operator>.arithmeticShiftRight").callIn.code(".*>>[ ]*8.*").toSet
          | cpg.method.name("<operator>.shiftLeft").callIn.code(".*<<[ ]*8.*").toSet);
val _socketCalls = cpg.call.name("sendall|sendsome|recvall|recvall").toSet;
val _rawSocketCalls = cpg.call.name("send|recv").toSet;

// Any call that we belive is protocol/socket logic
val _protocolRelatedLogicCalls = _protocolCalls | _socketCalls | _rawSocketCalls | byteHandlingCalls;
val _protocolRelatedLogicMethods = _protocolRelatedLogicCalls.method.toSet;

// Detect the filenames where there are methods of the call that mix logic with the protocol
// The mix may not happen within the same method but in two different methods of the same file
val _appLogicFilenames = _appLogicMethods.filename.toSet;
val _protocolRelatedLogicFilenames =  _protocolRelatedLogicMethods.filename.toSet;
val _mixingLogicFilenames = _appLogicFilenames & _protocolRelatedLogicFilenames;

// Now filter the methods that call cout/cin/... and the ones that do protocol/socket stuff.
// A single method may not do both but it will belong to a filename that has both.
// The hypothesis is that if even 2 methods are in the same filename, they belong to the same "unit" (or class)
// and they should not be mixing logic and protocol/socket.
val _appOrProtocolRelatedLogicMethods = (_appLogicMethods | _protocolRelatedLogicMethods);
val _mixingLogicMethods = _appOrProtocolRelatedLogicMethods.filter(method => _mixingLogicFilenames.contains(method.filename)).toSet;

// mixingLogic results:
//  - the has* attributes say if the method has either application logic (prints)
//    or protocol/socket logic (hton, recv, ...)
//  - a method may have both has* attributes in true but none should have both in false.
try {
  issuesDetected += ("mixingLogic" -> _mixingLogicMethods.zipWithIndex.map({case (method, ix) => {
    Map(
      "method" -> extractInfoFromMethod(method),
      "hasAppLogic" -> _appLogicMethods.contains(method),
      "hasProtocolSocketLogic" -> _protocolRelatedLogicMethods.contains(method),
      );
    }}).toJsonPretty);
} catch {
  case err => issuesDetected += ("mixingLogic" -> ("ERROR: " + err));
}


// Endianness handling by hand (shifts and masks)
try {
  issuesDetected += ("possibleEndiannessHandlingByHandCalls" -> byteHandlingCalls.zipWithIndex.map({case (call, ix) => {
    Map(
      "call" -> extractInfoFromCall(call),
      "method" -> extractInfoFromMethod(call.method),
      );
    }}).toJsonPretty);
} catch {
  case err => issuesDetected += ("possibleEndiannessHandlingByHandCalls" -> ("ERROR: " + err));
}



// maybeMisuseSendRecv results:
// Very-likely incorrect use of raw send/recv functions
try {
  issuesDetected += ("maybeMisuseSendRecv" -> _rawSocketCalls.zipWithIndex.map({case (call, ix) => {
    Map(
      "call" -> extractInfoFromCall(call),
      "method" -> extractInfoFromMethod(call.method),
      );
    }}).toJsonPretty);
} catch {
  case err => issuesDetected += ("maybeMisuseSendRecv" -> ("ERROR: " + err));
}


// Things like 'send_num' are too low level. Protocol should have high level method names (public)
/*
var possibleLowLevelProtocolMethods = _protocolRelatedLogicMethods.signature(raw".*(num|int|char).*\(.*").toSet;
try {
  issuesDetected += ("possibleLowLevelProtocolMethods" -> possibleLowLevelProtocolMethods.zipWithIndex.map({case (method, ix) => {
    Map(
      "method" -> extractInfoFromMethod(method),
      );
    }}).toJsonPretty);
} catch {
  case err => issuesDetected += ("possibleLowLevelProtocolMethods" -> ("ERROR: " + err));
}
*/



// Check printf scanf  str[n]?cmp str[n]?cpy memcmp
val cFuncCalls = cpg.call.name("str[n]?cmp|str[n]cpy|memcmp|printf|scanf").toSet;
try {
  issuesDetected += ("cFuncCalls" -> cFuncCalls.zipWithIndex.map({case (call, ix) => {
    Map(
      "call" -> extractInfoFromCall(call),
      "method" -> extractInfoFromMethod(call.method),
      );
    }}).toJsonPretty);
} catch {
  case err => issuesDetected += ("cFuncCalls" -> ("ERROR: " + err));
}

// Calls to malloc, realloc, calloc
val cAllocCalls = cpg.call.name(raw"(std[ ]*::[ ]*)?malloc|realloc|calloc|free").toSet; // TODO check this query
try {
  issuesDetected += ("cAllocCalls" -> cAllocCalls.zipWithIndex.map({case (call, ix) => {
    Map(
      "call" -> extractInfoFromCall(call),
      "method" -> extractInfoFromMethod(call.method),
      );
    }}).toJsonPretty);
} catch {
  case err => issuesDetected += ("cAllocCalls" -> ("ERROR: " + err));
}


// "Local" is a bad name but it is how Joern calls any variable, being local or not.
// In this case we search all the locals where from each local we have a method with the special
// name "<global>" which, obviosly means the locals not locals!
//
// Note: const/constexpr are ignored because Joern maps every STL type to ANY and swallows
// the const/static modifiers.
//
// The hack is to query the real source code with readSourceCodeOfLocal().
// This works to some extent: global variable definitions that span more than 1 line will not work
// because Joern's Local has only 1 line number and not a range.
val globalVarsLocals = cpg.local.where(locals => locals.method.name(raw"\<global\>")).filter(local => {
  val src = readSourceCodeOfLocal(local);
  val pattern = raw"(^(const|constexpr)([ ]|$$).*)|.*[ ](const|constexpr)([ ]|$$).*";

  !src.linesIterator.exists(_.matches(pattern));
}).toSet;
try {
  issuesDetected += ("globalVariables" -> globalVarsLocals.zipWithIndex.map({case (local, ix) => {
    Map(
      "local" -> extractInfoFromLocal(local),
      "method" -> extractInfoFromMethod(local.method.head),
      );
    }}).toJsonPretty);
} catch {
  case err => issuesDetected += ("globalVariables" -> ("ERROR: " + err));
}


// New/delete func calls
// This is not necessary a issue because there is legit use cases. However, allocating
// primitive types is not. TODO
val cppAllocCalls = cpg.call.name("<operator>.new.*").toSet | cpg.call.name("<operator>.delete.*").toSet;

// Pass by pointer. For primitive types like char or void, it is ok, for the rest
// it may indicate an incorrect use of pointers
// This is going to have false positives when we deal with polymorphism and non-primitive types
//
// Note: we do a filtering on codeNot(raw".*;[ ]*") to remove method declarations
// (we deal with definitions only, joern does not have a shortcut for this)
val _passByPtrParameters = cpg.parameter.typeFullName(raw".*\*.*").toSet;
val _passNativeByParamenters = cpg.parameter.typeFullName(raw"(bool|char|void)(\[\])?\*.*").toSet;
val maybeUnneededPassByPtrParameters = (_passByPtrParameters -- _passNativeByParamenters).where(parameter => parameter.method.codeNot(raw".*;[ ]*")).toSet
try {
  issuesDetected += ("maybeUnneededPassByPtr" -> maybeUnneededPassByPtrParameters.zipWithIndex.map({case (parameter, ix) => {
    Map(
      "parameter" -> extractInfoFromParameter(parameter),
      "method" -> extractInfoFromMethod(parameter.method),
      );
    }}).toJsonPretty);
} catch {
  case err => issuesDetected += ("maybeUnneededPassByPtr" -> ("ERROR: " + err));
}

// Pass by value non-trivially copiable (and potentially large) object like vector, string, map and list of any type
val _passByValueNonTrivialObjectsParameters = cpg.parameter.code(raw".*(:|\s|^)(vector|string|map|list)[^a-zA-Z0-9_].*").code("^[^&]*$").where(parameter => parameter.method.codeNot(raw".*;[ ]*")).toSet;
try {
  issuesDetected += ("passByValueNonTrivialObjects" -> _passByValueNonTrivialObjectsParameters.zipWithIndex.map({case (parameter, ix) => {
    Map(
      "parameter" -> extractInfoFromParameter(parameter),
      "method" -> extractInfoFromMethod(parameter.method),
      );
    }}).toJsonPretty);
} catch {
  case err => issuesDetected += ("passByValueNonTrivialObjects" -> ("ERROR: " + err));
}


// long strings TODO: probably not needed
// val longStrings = cpg.literal.typeFullName("char\\[\\d\\d+\\]").l;

// Functions defined outside a class identifier (maybe static or global)
//
// The _.signature(raw".*\..*") matches signature of the form Class.Method,
// the _.signature(raw".* main\s*\(.*") matches the 'main()' functions
// and the _.codeNot("(<empty>|<global>)") are functions or methods with no source code
// which are likely to be from the system/OS/stdlib
// The _.nameNot("^operator[ ]*<<") is to ignore these overload functions
//
// None of those represents student's real static/global functions so we search
// for any method that does not match any of those.
val globalFuncMethods = cpg.method.signatureNot(raw".*\..*").signatureNot(raw".* main\s*\(.*").codeNot("(<empty>|<global>)").nameNot(raw"^operator[ ]*<<").toSet
try {
  issuesDetected += ("globalFunctions" -> globalFuncMethods.zipWithIndex.map({case (method, ix) => {
    Map(
      "method" -> extractInfoFromMethod(method),
      );
    }}).toJsonPretty);
} catch {
  case err => issuesDetected += ("globalFunctions" -> ("ERROR: " + err));
}


// Local/Stack buffer allocations -------------------------------------------------------------------------
//
// The following catches buffers of all sizes except 2 and 3. We expect to see
// buffers of 2 or 3 elements but nor more or less.
// Things like char[1] or char[64] is likely to be incorrect.
// NOTE: querying over typeFullName is more robust because it pre-process the string
// removing syntactic-valid-but-regex-annyoing whitespace
// NOTE: typeFullName also resolves the 'defines' so if we have 'char buf[SIZE]', typeFullName should
// be 'char[64]' given  '#define SIZE 64'. NICE!
val stackBufferAllocatedLocals = cpg.local.typeFullName(raw".*\[[ ]*\d+[ ]*\].*").typeFullNameNot(raw".*\[[ ]*[23][ ]*\].*").toSet;
try {
  issuesDetected += ("stackBufferAllocated" -> stackBufferAllocatedLocals.zipWithIndex.map({case (local, ix) => {
    Map(
      "local" -> extractInfoFromLocal(local),
      "method" -> extractInfoFromMethod(local.method.head),
      );
  }}).toJsonPretty);
} catch {
  case err => issuesDetected += ("stackBufferAllocated" -> ("ERROR: " + err));
}

// Local std::vector-based buffers
// Like stackBufferAllocated, see for buffers of not-expected sizes.
val vectorBufferAllocatedCalls = (
  cpg.call.code(".*vector.*<[ ]*(uint8_t|int8_t|char)[ ]*>.*").code(raw".*\([ ]*\d+[ ]*\).*").codeNot(raw".*\([ ]*[23][ ]*\).*").toSet
);
try {
  issuesDetected += ("vectorBufferAllocated" -> vectorBufferAllocatedCalls.zipWithIndex.map({case (call, ix) => {
    Map(
      "call" -> extractInfoFromCall(call),
      "method" -> extractInfoFromMethod(call.method),
      );
  }}).toJsonPretty);
} catch {
  case err => issuesDetected += ("vectorBufferAllocated" -> ("ERROR: " + err));
}

// -------------------------------------------------------------------------

// Long functions/methods (more than 40 lines), ignoring global functions
val longMethods = cpg.method.where(method => method.internal.filter(_.numberOfLines > 40).nameNot("<global>")).toSet;
try {
  issuesDetected += ("longMethods" -> longMethods.zipWithIndex.map({case (method, ix) => {
    Map(
      "method" -> extractInfoFromMethod(method),
      );
  }}).toJsonPretty);
} catch {
  case err => issuesDetected += ("longMethods" -> ("ERROR: " + err));
}


// Methods with too many nested loops
val tooManyNestedLoopsMethods = cpg.method.where(method => method.internal
    .filter(
      _.ast.isControlStructure
        .controlStructureType("(FOR|DO|WHILE)")
        .size > 3
    )
    .nameNot("<global>")).toSet;

try {
  issuesDetected += ("tooManyNestedLoopsMethods" -> tooManyNestedLoopsMethods.zipWithIndex.map({case (method, ix) => {
    Map(
      "method" -> extractInfoFromMethod(method),
      );
    }}).toJsonPretty);
} catch {
  case err => issuesDetected += ("tooManyNestedLoopsMethods" -> ("ERROR: " + err));
}

// Return the methods that contains a 'switch' control structure that lacks of a 'default' clause.
// Note: we cannot use _.code or _.codeNot because Joern truncates results to large so trying to match
// anything that it is not in the first lines of the method will yield bad results.
var switchWithoutDefaultMethods = cpg.controlStructure.controlStructureType("SWITCH").method.filter(method => {
  val src = readSourceCodeOfMethod(method);
  val pattern = raw".*[ ]default[ ]*:.*";

  !src.linesIterator.exists(_.matches(pattern));
}).toSet;
try {
  issuesDetected += ("switchWithoutDefaultMethods" -> switchWithoutDefaultMethods.zipWithIndex.map({case (method, ix) => {
    Map(
      "method" -> extractInfoFromMethod(method),
      );
    }}).toJsonPretty);
} catch {
  case err => issuesDetected += ("switchWithoutDefaultMethods" -> ("ERROR: " + err));
}


var libErrorThrowCalls = cpg.call.name(raw"<operator>.throw").code(raw"throw[ ]+LibError.*").toSet;
try {
  issuesDetected += ("libErrorThrowCalls" -> libErrorThrowCalls.zipWithIndex.map({case (call, ix) => {
    Map(
      "call" -> extractInfoFromCall(call),
      "method" -> extractInfoFromMethod(call.method),
      );
  }}).toJsonPretty);
} catch {
  case err => issuesDetected += ("libErrorThrowCalls" -> ("ERROR: " + err));
}

// Likely the student is calling explicitly to lock()/try_lock() on a mutex instead of using unique_lock or similar
// Note: in the regex, the (->|.) is to ensure that we are seeing an attribute lookup; joern may see
// std::lock as a field access too.
var lockCalls = cpg.fieldAccess.code(raw".*(->|\.)[ ]*lock").toSet | cpg.fieldAccess.code(raw".*(->|\.)[ ]*try_lock").toSet;
try {
  issuesDetected += ("lockCalls" -> lockCalls.zipWithIndex.map({case (call, ix) => {
    Map(
      "call" -> extractInfoFromCall(call),
      "method" -> extractInfoFromMethod(call.method),
      );
  }}).toJsonPretty);
} catch {
  case err => issuesDetected += ("lockCalls" -> ("ERROR: " + err));
}


try {
  issuesDetected += ("moreThanOneMutex" -> s"$joernExternalHelperBin moreThanOneMutex ./".!!);
} catch {
  case err => issuesDetected += ("moreThanOneMutex" -> ("ERROR: " + err));
}


try {
  issuesDetected += ("stdThreadUsed" -> s"$joernExternalHelperBin stdThreadUsed ./".!!);
} catch {
  case err => issuesDetected += ("stdThreadUsed" -> ("ERROR: " + err));
}

// NOTE: this has some false negatives
// Eg: std::this_thread::sleep_for(milliseconds_to_sleep);  not a call???
var sleepCalls = cpg.call.name("(u)?sleep.*").toSet;
try {
  issuesDetected += ("sleepCalls" -> sleepCalls.zipWithIndex.map({case (call, ix) => {
    Map(
      "call" -> extractInfoFromCall(call),
      "method" -> extractInfoFromMethod(call.method),
      );
  }}).toJsonPretty);
} catch {
  case err => issuesDetected += ("sleepCalls" -> ("ERROR: " + err));
}


// Nice things to have for FefoBot 3.0:
//  - detect commented code: I have no idea how to do it, joern does not have support (apparently).
//  - remove the false positive of cout calls that are not app-logic but error-logic
//    Maybe we have to drop the cout idea and just search for literal strings that belong
//    to the app-logic (provided by the user) and then check if in the same .cpp file
//    there is a mix of app-logic and protocol-logic (mix at the file level, not the method level)
//    Check cpg.literal.code.l
//  - metodos publicos del protocolo deberia hablar en terminos del modelo: we could detect
//    the Protocol classes and then list its methods and search things like 'char' or 'byte' in their names
//    ref: https://github.com/Taller-de-Programacion-TPs/sockets-2024c2-josValentin-fiuba/issues/2
//  - no esta siendo detectado esto? https://github.com/Taller-de-Programacion-TPs/sockets-2024c2-FacuGerez/blob/be339912ab71c3719d57f43721a97cee79755222/client_protocolo.cpp#L16
//  - fix: las funciones de c (strcmp) podrian estar prefijadas con 'std::'
//  - detectar mix the htons y shifts
//  - this->name.compare("Not Equipped")  por '=='

val fefobot_running = System.getenv().getOrDefault("FEFOBOT_RUNNING", "0").toInt;
if (fefobot_running == 1) {
  import org.json4s.native.Json
  import org.json4s.DefaultFormats

  val issue_fname = System.getenv().getOrDefault("FEFOBOT_ISSUE_FNAME", "last-issues-detected-by-joern.json");

  val output = Json(DefaultFormats).write(issuesDetected)
  Files.write(Paths.get(issue_fname), output.getBytes(StandardCharsets.UTF_8))

  exit
}