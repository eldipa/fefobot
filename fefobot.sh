#!/usr/bin/env bash

set -e


usage() {
    echo "Usage: $0 [-f|--force-clone] <workdir> <course> [sockets|threads] <username> [<release>]"
    echo "Example: $0 /home/user/correcciones/01/ 2024c2 sockets student-github-user v42"
    echo "Example: $0 /home/user/correcciones/02/ 2024c2 threads student-github-user"
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
            if [ -z "$workdir" ]; then
                workdir="$1"
            elif [ -z "$course" ]; then
                course="$1"
            elif [ -z "$exercise" ]; then
                exercise="$1"
            elif [ -z "$username" ]; then
                username="$1"
            elif [ -z "$release" ]; then
                release="$1"
            else
                echo "Unknown argument: $1"
                usage
            fi
            ;;
    esac
    shift
done

if [ -z "$username" -o -z "$exercise" -o -z "$course" -o -z "$workdir" ]; then
    usage
fi

if [ -z "$release" ]; then
    echo "No release was selecting, defaulting to the latest one"
    release=LATEST # HACK
fi

if [ ! -d "$workdir" ]; then
    echo "Working directory '$workdir' does not exist. Please, create manually one before continuing."
    echo
    usage
    exit 1
fi

# Where fefobot's scripts live
SCRIPT_DIR=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )

# Ensure that we can run the scripts
if [ ! -x "$SCRIPT_DIR/inject_source_code.sh" ]; then
    echo "'$SCRIPT_DIR/inject_source_code.sh' is not executable. Abort."
    exit 1
fi

if [ ! -x "$SCRIPT_DIR/issue_processor" ]; then
    echo "'$SCRIPT_DIR/issue_processor' is not executable. Abort."
    exit 1
fi


repo_name="$exercise-$course-$username"

public_git_repo="$repo_name-$release"
private_joern_repo="$public_git_repo-joern"

# Clone the repository, checkout to the tag that the given release has associated
# create a branch named like the folder where the repository lives and create
# the REPO_HEAD.fefobot file that has the commit hash of the current HEAD.
clone_release() {
    local repo_name; repo_name=$1
    local release; release=$2
    local dst_folder; dst_folder=$3
    local force_clone; force_clone=$4

    if [ -z "$repo_name" ]; then
        echo "Bad 'repo_name' parameter for 'clone_release': '$repo_name'. Abort"
        exit 1
    fi

    if [ -z "$release" ]; then
        echo "Bad 'release' parameter for 'clone_release': '$release'. Abort"
        exit 1
    fi

    if [ -z "$dst_folder" ]; then
        echo "Bad 'dst_folder' parameter for 'clone_release': '$dst_folder'. Abort"
        exit 1
    fi

    if [ "$force_clone" != "0" -a "$force_clone" != "1" ]; then
        echo "Bad 'force_clone' parameter for 'clone_release': '$force_clone'. Abort"
        exit 1
    fi

    # The destination folder already exists but the user wants to do a clone anyways
    if [ -d "$dst_folder" -a "$force_clone" = "1" ]; then
        echo "Destination folder '$dst_folder' already exists. Removing it because force_clone=$force_clone"
        rm -fr "$dst_folder"
    fi

    if [ -d "$dst_folder" ]; then
        echo "Destination folder '$dst_folder' already exists. Skipping."
        return 0
    fi

    echo "Cloning $repo_name, release $release into $dst_folder"
    gh repo clone "Taller-de-Programacion-TPs/$repo_name" "$dst_folder"

    cd "$dst_folder"

    gh release list > RELEASES
    local tag;

    if [ "$release" = 'LATEST' ]; then
        tag=$(cat RELEASES | grep '\sLatest\s' | sed 's/\sLatest\s/\t/g' | sed 's/\sPre-release\s/\t/g' | awk -F'\t' '{print $2}' | head -1)
    else
        tag=$(cat RELEASES | sed 's/\sLatest\s/\t/g' | sed 's/\sPre-release\s/\t/g' | grep "^$release\s" | awk -F'\t' '{print $2}' | head -1)
    fi

    echo "Releases found:"
    cat RELEASES
    rm RELEASES

    if [ -z "$tag" ]; then
        echo "Tag for release '$release' not found. Abort"
        exit 1
    fi

    echo "Checkout & branching tag '$tag' (release: '$release')"

    git checkout -b "$dst_folder-$tag" "$tag"

    local commit_hash; commit_hash=$(git rev-parse HEAD)
    cd ..

    # copy the hash outside of the dst folder
    echo "$commit_hash" > "$dst_folder-REPO_HEAD.fefobot"
}

prepare_working_copy_for_joern() {
    local src_git_repo; src_git_repo=$1
    local dst_joern_folder; dst_joern_folder=$2

    if [ -z "$src_git_repo" -o ! -d "$src_git_repo" ]; then
        echo "Bad 'src_git_repo' parameter for 'prepare_working_copy_for_joern': '$src_git_repo'. Abort"
        exit 1
    fi

    if [ -z "$dst_joern_folder" ]; then
        echo "Bad 'dst_joern_folder' parameter for 'prepare_working_copy_for_joern': '$dst_joern_folder'. Abort"
        exit 1
    fi

    if [ ! -d "$dst_joern_folder" ]; then
        echo "Working copy for Joern does not exist, copying one"
        cp -R "$src_git_repo" "$dst_joern_folder"
    else
        echo "Reusing existing working copy for Joern"
    fi

    # Do the following even if we are reusing an existing working copy.
    # It should have cost zero.

    echo "Removing files of no interest before running Joern"
    cd "$dst_joern_folder"

    # Delete any non source code file recursively
    find . -type f -a \( -not -name '*.cpp' -a -not -name '*.h' -a -not -name '*.fefobot' \) -delete

    # Delete any file provided by the course
    rm $(grep -Rl --include '*.cpp' 'ssize_t __real_send.int sockfd,') 2>/dev/null || true # common_wrap_socket.cpp

    rm $(grep -Rl --include '*.cpp' 'empieza la magia arcana proveniente de C') 2>/dev/null || true # liberror.cpp
    rm $(grep -Rl --include '*.cpp' 'LibError::LibError.int[ ]\+error_code,[ ]\+const[ ]\+char.[ ]\+fmt,[ ]\+\.\.\..[ ]\+noexcept') 2>/dev/null || true # liberror.cpp

    rm $(grep -Rl --include '*.cpp' 'obtenida tenemos que ver cual es realmente funcional') 2>/dev/null || true # socket.cpp
    rm $(grep -Rl --include '*.cpp' 'skt[ ]*=[ ]*socket.addr->ai_family,[ ]*addr->ai_socktype,[ ]*addr->ai_protocol.;') 2>/dev/null || true # socket.cpp

    rm $(grep -Rl --include '*.cpp' 'ResolverError::ResolverError.*int.*gai_errno.*') 2>/dev/null || true # resolvererror.cpp

    rm $(grep -Rl --include '*.cpp' 'Para pre-seleccionar que direcciones nos interesan le pasamos') 2>/dev/null || true # resolver.cpp
    rm $(grep -Rl --include '*.cpp' 'hints.ai_flags[ ]*=[ ]*is_passive[ ]*.[ ]*AI_PASSIVE[ ]*:[ ]*0;') 2>/dev/null || true # resolver.cpp

    rm $(grep -Rl --include '*.h' 'Dado que `errno` es una variable global y puede ser modificada por') 2>/dev/null || true # liberror.h
    rm $(grep -Rl --include '*.h' 'class[ ]\+LibError[ ]*:[ ]*public[ ]\+std::exception') 2>/dev/null || true # liberror.h

    rm $(grep -Rl --include '*.h' 'Muchas librer.as de muchos lenguajes ofrecen una .nica formal de inicializar') 2>/dev/null || true # socket.h
    rm $(grep -Rl --include '*.h' '[ ]Socket(const[ ]\+char.[ ]\+hostname,[ ]*const[ ]*char.[ ]*servname);') 2>/dev/null || true # socket.h

    rm $(grep -Rl --include '*.h' 'Clase que encapsula un "gai" error. Vease getaddrinfo') 2>/dev/null || true # resolvererror.h
    rm $(grep -Rl --include '*.h' 'class[ ]\+ResolverError[ ]*:[ ]*public[ ]\+std::exception') 2>/dev/null || true # resolvererror.h

    rm $(grep -Rl --include '*.h' 'Si `is_passive` es `true` y `hostname` es `nullptr`,') 2>/dev/null || true # resolver.h
    rm $(grep -Rl --include '*.h' '[ ]struct[ ]\+addrinfo.[ ]\+result;') 2>/dev/null || true # resolver.h

    rm $(grep -Rl --include '*.h' 'Multiproducer/Multiconsumer Blocking Queue .MPMC.') 2>/dev/null || true # queue.h
    rm $(grep -Rl --include '*.h' '[ ]Queue():[ ]*max_size.UINT_MAX[ ]*-[ ]*1.,[ ]*closed.false.[ ]*{') 2>/dev/null || true # queue.h

    rm $(grep -Rl --include '*.h' 'flags, mostly to control how Thread::run.. will behave') 2>/dev/null || true # thread.h
    rm $(grep -Rl --include '*.h' 'class[ ]\+Thread:[ ]*public[ ]\+Runnable[ ]*{') 2>/dev/null || true # thread.h

    # Delete directories that may have emptied after deleting files
    find . -type d -empty -delete

    # Rename any .h (header file) to .cpp because Joern does not seem to be reading them!
    # The new .h.cpp compounded extension will be have to be postprocessed by the issue_processor
    echo "Rename headers .h as .h.cpp so Joern can detect them"
    find . -type f -a -name '*.h' -exec mv {} {}.cpp \;

    # Inject some code into each source code
    # this is needed to workaround some bugs of Joern
    echo "Inject helper code into source code files to assists Joern"
    find . -type f -name '*.cpp' -exec "$SCRIPT_DIR/inject_source_code.sh" {} "$SCRIPT_DIR/injection" \;

    cd ..
}

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


# Move to the working directory
cd "$workdir/"

# Download the repository and checkout the release
clone_release "$repo_name" "$release" "$public_git_repo" "$force_clone"

# Load the commit hash created by clone_release
commit_hash=$(cat "$public_git_repo-REPO_HEAD.fefobot")
if [ -z "$commit_hash" ]; then
    echo "Commit hash not found or found empty. Abort."
    exit 1
fi

# Prepare the working copy and run Joern
issue_json_fname="issues-$public_git_repo.json"
issue_md_fname="issues-$public_git_repo.md"
prepare_working_copy_for_joern "$public_git_repo" "$private_joern_repo"

run_joern_issue_detection "$private_joern_repo" "$issue_json_fname" "/home/user/bin/joern/joern-cli/joern"

echo "Issues detected by Joern saved in $issue_json_fname"

# The sourceCodeOffset allows us to tell Joern that the source line numbers should be offset by this amount
# Because in the step above we injected code, this made the code to have an offset that the original code
# does not have so we need to compensate it.
sourceCodeOffset="$(wc -l "$SCRIPT_DIR/injection" | awk '{print $1}')"

if grep -q -R 'fefobot-annotation' "$public_git_repo" ; then
    echo "Source code '$public_git_repo' already annotated. Skip."
else
    echo "Annotating source code with the issues detected"

    set -x
    "$SCRIPT_DIR/issue_processor" "annotate" "$issue_json_fname" "$public_git_repo" "$sourceCodeOffset"
    set +x
fi

echo "Creating markdown..."
set -x
"$SCRIPT_DIR/issue_processor" "format" "$issue_json_fname" "$repo_name" "$commit_hash" "$sourceCodeOffset"
set +x
echo "Markdown file saved in $issue_md_fname"
