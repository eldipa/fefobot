#!/usr/bin/env bash

set -e


usage() {
    echo "Usage: $0 <username> <exercise> <course>"
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
            else
                echo "Unknown argument: $1"
                usage
            fi
            ;;
    esac
    shift
done

if [ -z "$username" ] || [ -z "$exercise" ] || [ -z "$course" ]; then
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
    echo "$commit_hash" > "$path/REPO_HEAD.h"
}

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

echo "Running Joern"

cd "$path"
# delete any non source code file recursively
find . -type f -not -name '*.cpp' -a -type f -not -name '*.h' -a -type f -not -name 'Makefile' -delete
# delete directories that may have emptied after deleting files
find . -type d -empty -delete

TERM=dumb /home/user/bin2/joern/joern-cli/joern --nocolors < ../joern_commands.scala > /dev/null

mv "issues-$path.json" ..
cd ..

echo "Output queries saved in" "issues-$path.json"
echo "Building markdown file"

./markdown_issue_builder "issues-$path.json" "$commit_hash"

echo "Markdown file saved in" "issues-$path.md"


