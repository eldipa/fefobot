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
        commit_hash=$(cat "$path/REPO_HEAD.fefobot")
    fi
else
    clone "$path"
    save_head
fi

# This part is a little bit hackish, but it seems to be working ok
echo "Adapting source code before running joern"

cd "$path"

# Delete any non source code file recursively
find . -type f -a \( -not -name '*.cpp' -a -not -name '*.h' -a -not -name '*.fefobot' \) -delete

# Delete any file provided by the course
rm $(grep -Rl --include '*.cpp' 'empieza la magia arcana proveniente de C') 2>/dev/null || true # liberror.cpp
rm $(grep -Rl --include '*.cpp' 'obtenida tenemos que ver cual es realmente funcional') 2>/dev/null || true # socket.cpp
rm $(grep -Rl --include '*.cpp' 'ResolverError::ResolverError.*int.*gai_errno.*') 2>/dev/null || true # resolvererror.cpp
rm $(grep -Rl --include '*.cpp' 'Para pre-seleccionar que direcciones nos interesan le pasamos') 2>/dev/null || true # resolver.cpp

rm $(grep -Rl --include '*.h' 'Dado que `errno` es una variable global y puede ser modificada por') 2>/dev/null || true # liberror.h
rm $(grep -Rl --include '*.h' 'Muchas librer.as de muchos lenguajes ofrecen una .nica formal de inicializar') 2>/dev/null || true # socket.h
rm $(grep -Rl --include '*.h' 'Clase que encapsula un "gai" error. Vease getaddrinfo') 2>/dev/null || true # resolvererror.h
rm $(grep -Rl --include '*.h' 'Si `is_passive` es `true` y `hostname` es `nullptr`,') 2>/dev/null || true # resolver.h

rm $(grep -Rl --include '*.h' 'Multiproducer/Multiconsumer Blocking Queue .MPMC.') 2>/dev/null || true # queue.h
rm $(grep -Rl --include '*.h' 'flags, mostly to control how Thread::run.. will behave') 2>/dev/null || true # thread.h

# Delete directories that may have emptied after deleting files
find . -type d -empty -delete


# inject some code into each source code
# this is needed to workaround some bugs of joern
find . -type f \( -name '*.cpp' -o -name '*.h' \) -a -not -name REPO_HEAD.fefobot -exec "$SCRIPT_DIR/inject_source_code.sh" {} "$SCRIPT_DIR/injection" \;

echo "Running Joern"
# The sourceCodeOffset allows us to tell joern that the source line numbers should be offset by this amount
# Because in the step above we injected code, this made the code to have an offset that the original code
# does not have so we need to compensate it.
sourceCodeOffset="$(wc -l "$SCRIPT_DIR/injection" | awk '{print $1}')" TERM=dumb /home/user/bin/joern/joern-cli/joern --nocolors < "$SCRIPT_DIR/joern_commands.scala"

mv "issues-$path.json" ..
cd ..

echo "Output queries saved in" "issues-$path.json"
echo "Building markdown file"

"$SCRIPT_DIR/markdown_issue_builder" "issues-$path.json" "$commit_hash"

echo "Markdown file saved in" "issues-$path.md"


