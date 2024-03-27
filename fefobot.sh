#!/usr/bin/env bash

set -e


usage() {
    echo "Usage: $0 <username> <exercise> [course]"
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

if [ -z "$username" ] || [ -z "$exercise" ]; then
    usage
fi

course=${course:-"2024c1"}
path=$exercise-$course-$username


clone() {
    echo "Cloning $exercise repo from student $username. $course"
    gh repo clone Taller-de-Programacion-TPs/$1
}

if [ -d "$path" ]; then
    if [ $force_clone -eq 1 ]; then
        rm -rI "$path"
        clone "$path"
    else
        echo "$path exists, will not clone"
    fi
else
    clone "$path"
fi

# this part is a little bit hackish, but it seems to be working ok

echo "Running Joern"

cd "$path"
# delete any non source code file recursively
find . -type f -not -name '*.cpp' -a -not -name '*.h' -a -not -name '.' -delete
# delete directories that may have emptied after deleting files
find . -type d -empty -delete

joern --nocolors < ../joern_commands.scala > /dev/null 2> /dev/null

mv issues-"$path".json ..
cd ..

echo "Output queries saved in" issues-"$path".json
