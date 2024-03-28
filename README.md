# Fefobot
## Instructions

```shell
git clone git@github.com:eldipa/fefobot.git
chmox u+x markdown_issue_builder
pip install -r requirements.txt
./fefobot.sh <username> <exercise> <course>
```

Username is the username of the student, exercise is one of (sockets, threads) and course
is 202xcx (example: 2024c1).
You may use the -f/--force-clone flag if you wish to remove the student repo and start clean.


## Queries

Fetch all the repositories for the given exercise+course

```shell
exercise=sockets
course=2023c2

gh repo list Taller-de-Programacion-TPs -L 600 --json name --jq '.[].name' | grep -v 'template' | grep '$exercise-$course' > allrepos

for r in $(grep '$exercise-$course' allrepos ); do
    gh repo clone Taller-de-Programacion-TPs/$r
    pushd "$r"

    # check that it has 'main' only

    # delete any non source code file
    # TODO: find finds directories that may get empty during the
    # deletions and delete thems as well (GOOD) but if the directory is
    # not left empty (because there is a .cpp or .h there), find will
    # try to delete, it will fail and it will complain (BAD). That's why
    # we have a grep and a "|| true"
    find . -depth -not -name '*.cpp' -a -not -name '*.h' -a -not -name '.'  -delete 2>&1 | grep -v 'Directory not empty' || true

    sleep 2
    popd
done
```


Posts:
 - https://jaiverma.github.io/blog/joern-uboot
 - https://jaiverma.github.io/blog/joern-intro
 - https://blog.shiftleft.io/zero-day-snafus-hunting-memory-allocation-bugs-797e214fab6c?gi=b05a8c5242f9

Mini challenges:
 - https://github.com/jaiverma/joern-queries/tree/master/flow

Queries:
 - https://queries.joern.io/



Find all the methods that call C-like heap
```
cpg.call.name("malloc|realloc|calloc").method.l
```

Find all the methods' names of code that looks like to be doing
protocol-related stuff
```
cpg.call.name("hton[sl]|ntoh[sl]").method.fullName.l
```

find new / delete calls:
 - unnecessary news
 - no-symmetric new/deletea

```
cpg.call.name("<operator>.new").repeat(_.astParent)(_.until(_.isMethod)).l
cpg.call.name("<operator>.delete").repeat(_.astParent)(_.until(_.isMethod)).l
```


Methods that uses `cout` or `std::cout`

```
val printing1 = cpg.identifier.name("cout").repeat(_.astParent)(_.until(_.isMethod)).toSet
val printing2 = cpg.fieldAccess.code("std.*::.*cout").repeat(_.astParent)(_.until(_.isMethod)).toSet

(printing1 ++ printing2).l
```


Method that pass args by pointer
(sets: https://docs.scala-lang.org/overviews/collections/sets.html)
```
val byPtr = cpg.parameter.typeFullName(".*\\*.*").toSet
val nativePtr = cpg.parameter.typeFullName("(bool|char|void)(\\[\\])?\\*.*").toSet

(byPtr -- nativePtr).l
```


Strings (mas de 10 chars)
```
cpg.literal.typeFullName("char\\[\\d\\d+\\]").l
```


Pasaje por copia? (vector, list, map, string)
NOTA: el type del parametro para `vector` es `ANY` (lo mismo pasa con
los signatures). No se puede usar asi
que hay q hacer regex-magic sobre el `code`
Esta query busca todos los vector/string/... q no tengan un `&` en el
nombre (cazaria al pasaje por copia/move/pointer)

https://docs.oracle.com/javase/tutorial/essential/regex/pattern.html
```
cpg.method.parameter.where(_.code(".*(:|\\s|^)(vector|string|map|list)[^a-zA-Z0-9_].*")).where(_.code("^[^&]*$")).repeat(_.astParent)(_.until(_.isMethod)).l
```


```
cpg.call.name("atoi").astParent.l
```


Buscar funciones por fuera de clases
```
cpg.method.whereNot(_.signature(".*\\..*")).whereNot(_.signature(".* main\\s*\\(.*")).whereNot(_.code("(<empty>|<global>)")).l
```

Definiciones de buffers

```
cpg.local.typeFullName(".*\\[.*").repeat(_.astParent)(_.until(_.isMethod)).l
```




Como se puede ignorar un file en particular? Borrarlo del repo?
Como se puede detectar un recvsome/sendsome con un size != 1 (lo q
implicaria 99% q esta mal y deberia usarse recvall/sendall)






too-long
More than 50 lines

This query identifies functions that are more than 50 lines long
CPGQL Query:

```
({cpg.method.internal.filter(_.numberOfLines > 50).nameNot("<global>")}).l
```

too-many-loops  (maybe)
More than 3 loops

This query identifies functions with more than 3 loops
CPGQL Query:

```
({cpg.method.internal
  .filter(
    _.ast.isControlStructure
      .controlStructureType("(FOR|DO|WHILE)")
      .size > 3
  )
  .nameNot("<global>")}).l
```

### Untested

https://queries.joern.io/

too-high-complexity
Cyclomatic complexity higher than 4

This query identifies functions with a cyclomatic complexity higher than 4
CPGQL Query:

```
({cpg.method.internal.filter(_.controlStructure.size > n).nameNot("<global>")}).l
```






too-many-params
Number of parameters larger than 4

This query identifies functions with more than 4 formal parameters
CPGQL Query:

```
({cpg.method.internal.filter(_.parameter.size > n).nameNot("<global>")}).l
```


too-nested
Nesting level higher than 3

This query identifies functions with a nesting level higher than 3
CPGQL Query:

```
({cpg.method.internal.filter(_.depth(_.isControlStructure) > n).nameNot("<global>")}).l
```


({cpg.method("(?i)gets").callIn}).l
({cpg.method("(?i)scanf").callIn}).l
({cpg.method("(?i)(strcat|strncat)").callIn}).l
({cpg.method("(?i)(strcpy|strncpy)").callIn}).l
({cpg.method("(?i)strtok").callIn}).l





- Magic numbers
 - Argumentos del Protocolo: objetos de alto nivel
 - detectar codigo de debugging / codigo comentado
