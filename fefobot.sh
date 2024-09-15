#!/usr/bin/env bash

set -e


usage() {
    echo "Usage: $0 <username> <exercise> <course> <workdir>"
    echo "Example: $0 student-github-user socket 2024c2 /home/user/correcciones/"
    exit 1
}

force_clone=0

while [[ $# -gt 0 ]]; do
    key="$1"
    case $key in
        -f|--force-clone)
            force_clone=1
            ;;
        *)
            if [ -z "$username" ]; then
                username="$1"
            elif [ -z "$exercise" ]; then
                exercise="$1"
            elif [ -z "$course" ]; then
                course="$1"
            elif [ -z "$workdir" ]; then
                workdir="$1"
            else
                echo "Unknown argument: $1"
                usage
            fi
            ;;
    esac
    shift
done

if [ -z "$username" ] || [ -z "$exercise" ] || [ -z "$course" ] || [ -z "$workdir" ]; then
    usage
fi

path=$exercise-$course-$username

clone() {
    echo "Cloning $exercise repo from student $username. $course"
    gh repo clone "Taller-de-Programacion-TPs/$1"
}

save_head() {
    cd "$path"
    commit_hash=$(git rev-parse HEAD)
    cd ..
    echo "$commit_hash" > "$path/REPO_HEAD.fefobot"
}

if [ ! -d "$workdir" ]; then
    echo "Working directory '$workdir' does not exist. Create one before continuing."
    echo
    usage
    exit 1
fi

# Where fefobot's scripts live
SCRIPT_DIR=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )

# Ensure that we can run the scripts
chmod u+x "$SCRIPT_DIR/inject_source_code.sh" "$SCRIPT_DIR/markdown_issue_builder"

# Move to the working directory
cd "$workdir/"

# Download the repository
if [ -d "$path" ]; then
    if [ $force_clone -eq 1 ]; then
        echo "Removing $path"
        rm -rI "$path"
        clone "$path"
        save_head
    else
        echo "$path exists, will not clone"
        commit_hash=$(cat "$path/REPO_HEAD.h")
    fi
else
    clone "$path"
    save_head
fi

# this part is a little bit hackish, but it seems to be working ok
echo "Adapting source code before running joern"
chmod u+x inject_source_code.sh

cd "$path"
# delete any non source code file recursively
find . -type f -not -name '*.cpp' -a -type f -not -name '*.h' -a -type f -not -name 'Makefile' -delete
# delete directories that may have emptied after deleting files
find . -type d -empty -delete
# inject some code into each source code
find . -type f \( -name '*.cpp' -o -name '*.h' \) -a -not -name REPO_HEAD.h -exec ../inject_source_code.sh {} ../injection \;

echo "Running Joern"
sourceCodeOffset="$(wc -l ../injection | awk '{print $1}')" TERM=dumb /home/user/bin2/joern/joern-cli/joern --nocolors < ../joern_commands.scala

mv "issues-$path.json" ..
cd ..

echo "Output queries saved in" "issues-$path.json"
echo "Building markdown file"

"$SCRIPT_DIR/markdown_issue_builder" "issues-$path.json" "$commit_hash"

echo "Markdown file saved in" "issues-$path.md"


