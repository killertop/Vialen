#!/bin/bash

source ../buildScript/lib/core/get_source_env.sh || exit 1

chmod -R 777 .build 2>/dev/null
rm -rf .build 2>/dev/null

if [ -z "$GOPATH" ]; then
    GOPATH=$(go env GOPATH)
fi

# Install gomobile
if [ ! -f "$GOPATH/bin/gomobile-matsuri" ]; then
    git clone https://github.com/MatsuriDayo/gomobile.git
    pushd gomobile
	git checkout --detach "$COMMIT_GOMOBILE" || exit 1
    pushd cmd
    pushd gomobile
    go install -v
    popd
    pushd gobind
    go install -v
    popd
    popd
    rm -rf gomobile
    mv "$GOPATH/bin/gomobile" "$GOPATH/bin/gomobile-matsuri"
    mv "$GOPATH/bin/gobind" "$GOPATH/bin/gobind-matsuri"
fi

# Existing tool binaries must also match the pinned source revision.
for tool in gomobile-matsuri gobind-matsuri; do
    tool_info=$(go version -m "$GOPATH/bin/$tool") || exit 1
    case "$tool_info" in
        *"vcs.revision=$COMMIT_GOMOBILE"*) ;;
        *) echo "$tool source revision does not match COMMIT_GOMOBILE" >&2; exit 1 ;;
    esac
    case "$tool_info" in
        *"vcs.modified=false"*) ;;
        *) echo "$tool was built from modified or unverified sources" >&2; exit 1 ;;
    esac
done

GOBIND=gobind-matsuri gomobile-matsuri init
