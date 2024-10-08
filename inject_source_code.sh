#!/bin/bash

if [ "$#" != 2 ]; then
    exit 1
fi

if [ ! -f "$1" -o ! -f "$2" ]; then
    exit 2
fi

target=$1
injectionfile=$2

if ! grep -q 'FEFOBOT - INJECTION' "$target" ; then
    cat "$injectionfile" "$target" > .injected_fefobot_tmp_file
    mv .injected_fefobot_tmp_file "$target"
fi
