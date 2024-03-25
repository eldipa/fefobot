importCode(inputPath=".", projectName=System.getProperty("user.dir").split("/").last)

val printing1 = cpg.identifier.name("cout").repeat(_.astParent)(_.until(_.isMethod)).toSet
val printing2 = cpg.fieldAccess.code("std.*::.*cout").repeat(_.astParent)(_.until(_.isMethod)).toSet

(printing1 ++ printing2).l

exit