#!/usr/bin/env bash

set -e

usage() {
    echo "Usage: $0 [case1 case2...]"
    echo "Example: $0   # this will run the whole suite"
    echo "Example: $0 fefobot-tests/tests/cases/threads-2024c2*  # this will run only those test cases"
    exit 1
}

# Where fefobot's scripts live
SCRIPT_DIR=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )

run_joern_issue_detection() {
    local dst_folder; dst_folder=$1
    local issue_fname; issue_fname=$2
    local joern_bin; joern_bin=$3

    if [ -z "$dst_folder" -o ! -d "$dst_folder" ]; then
        echo "Bad 'dst_folder' parameter for 'run_joern_issue_detection': '$dst_folder'. Abort"
        exit 1
    fi

    if [ -z "$issue_fname" ]; then
        echo "Bad 'issue_fname' parameter for 'run_joern_issue_detection': '$issue_fname'. Abort"
        exit 1
    fi

    if [ ! -x "$joern_bin" ]; then
        echo "Bad 'joern_bin' parameter for 'run_joern_issue_detection': '$joern_bin'. Abort"
        exit 1
    fi

    cd "$dst_folder"

    # Hey! You can run joern by hand (interactively) running the same command than above
    # *including* the importer.scala part *except* using the env variables FEFOBOT_* and TERM
    # (and of course, the --nocolors is not needed either)
    echo "Running Joern"
    set -x
    echo -e "//> using file $SCRIPT_DIR/joern_commands.scala" > ../importer.scala
    FEFOBOT_RUNNING=1 FEFOBOT_ISSUE_FNAME="$issue_fname" TERM=dumb "$joern_bin" --nocolors < ../importer.scala 2>&1 | tee ../joern_last_run.log
    set +x

    if [ ! -s "$issue_fname" ]; then
        echo "Joern did not produced any valid issues file (either it is empty or it does not exit). Abort."
        exit 1
    fi

    # move the issues file out
    mv "$issue_fname" ..
    cd ..
}

do_test() {
    local l; l=$1
    local sourceCodeOffset; sourceCodeOffset=$2

    run_joern_issue_detection "$l" "$l.json" "/home/user/bin/joern/joern-cli/joern"
    "$SCRIPT_DIR/issue_processor" "serialize" "$l.json" "$l" "$sourceCodeOffset"

    mv "$l.test_results.md" ../obtained/
    rm -f "$l.json"
}

sourceCodeOffset="$(wc -l "$SCRIPT_DIR/injection" | awk '{print $1}')"

cd fefobot-tests/tests/
mkdir -p obtained

pushd .
cd cases/

if [ "$#" = 0 ]; then
    for l in $(echo *joern | tr " " "\n"); do
        do_test "$l" "$sourceCodeOffset"
    done
else
    for l in "$@"; do
        l="$(basename "$l")"
        do_test "$l" "$sourceCodeOffset"
    done
fi

popd
diff obtained/ expected/
