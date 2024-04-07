#!/bin/bash

if [ "$#" != 1 ]; then
    exit 1
fi

if [ ! -f "$1" ]; then
    exit 2
fi

target=$1

if grep -q 'FEFOBOT - INJECTION' "$target" ; then
    echo ""
else
    cat - "$target" > .injected_fefobot_tmp_file <<EOF
/* FEFOBOT - INJECTION */
#define try          if(true)
#define catch(...)   if(false)
EOF

    mv .injected_fefobot_tmp_file "$target"
fi
