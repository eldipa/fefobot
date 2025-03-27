# Fefobot
## Instructions

## Install

Prerequisites:
 - Python 3
 - Joern [installed and available in PATH](https://docs.joern.io/installation/). Use version `v2.0.128`

```shell
$ git clone git@github.com:eldipa/fefobot.git

$ cd fefobot
$ chmod u+x issue_processor fefobot.sh test.sh
```

**Note:** The code was tested using `joern` `v2.0.128`. Other version
may work. If so, leave it a comment here.

## Getting and using Fefobot

Go to where you want to work and create a working directory

```shell
$ cd ~/
$ mkdir wd
```

Now, run `fefobot.sh`

```shell
$ ~/fefobot/fefobot.sh wd/ 2024c1 sockets jdoe [release]
```

The `release` is optional. If not selected it will use the latest
release.

The rest of the params should be self-descripting:
 - the course
 - the exercise (sockets or threads)
 - the student's github user


You may use the `-f/--force-clone` flag if you wish to remove the student repo and start clean.

Running `fefobot` will:
 - Clone the student's github repository and checkout the tag associated
   with the release (with the one provided or the latest)
   The repository will be at `wd/sockets-2024c1-jdoe-release` (it is a git
   repository)
 - A normalized and simplified repository is created, needed to run
   `joern`
   This will be at `wd/sockets-2024c1-jdoe-release-joern` (not a git repository)
 - Run `joern` with the queries and code of `joern_commands.scala`
 - Create a markdown with the issues found, at `wd/issues-sockets-2024c1-jdoe-release.md`
   This markdown can be copy-and-pasted in Github.
 - Annotate the student's source code at `wd/sockets-2024c1-jdoe-release`
   with the issues found.
   The reviewer can go to that folder and run `git diff` to see what
   `joern` found.
   It can also do `git restore` to clean up and then add his/her
   observations (writing in the source code) and commit-and-push those
   to the student's repo.

**Note:** the queries may have false positives, the reviewer **must**
check them and remove them. It can also contain false negatives
so the reviewer **must** manually review the code.

## Run the test

```shell
$ ./test.sh  # run all the tests
$ ./test.sh xxx yyy  # run only those
```

This will run only `joern` over the repositories in `test/cases/`, get a
simplified issue list, write it in `test/obtained/` and compare it
against the expected in `test/expected/`

**Note:** the expected files may contain false positives and false
negatives. They are not super-quality tests. Improve them!.

### Add a test

After running `fefobot.sh` against some students repositories, copy
their `*-joern/` folders into `test/cases` and that's it.

Add tests that covers currently untested false positives or negatives.

## Change or add new queries

Just edit `joern_commands.scala`. Check the source code to see how it
works.

Run `./test.sh` to check how much detects and update the tests
accordingly.

If everything is ok, modify `issue_processor` and, if you added new
queries, add new `format_*` and `annotate_*` methods to `IssueFormatter`
and `IssueAnnotator` classes.

Now you are ready to run `fefobot.sh` and check that everything works
(the markdown is correctly generated and the source code is correctly
annotated).

## References

Posts:
 - https://jaiverma.github.io/blog/joern-uboot
 - https://jaiverma.github.io/blog/joern-intro
 - https://blog.shiftleft.io/zero-day-snafus-hunting-memory-allocation-bugs-797e214fab6c?gi=b05a8c5242f9

Mini challenges:
 - https://github.com/jaiverma/joern-queries/tree/master/flow

Queries:
 - https://queries.joern.io/

