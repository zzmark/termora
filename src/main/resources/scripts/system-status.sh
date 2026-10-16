export LC_ALL=C
section() {
    printf '__TERMORA_%s__\n' "$1"
    name=$1
    shift
    "$@"
    result=$?
    printf '\n__TERMORA_%s_EXIT__%s\n' "$name" "$result"
}
section OS uname -s
section CPU cat /proc/stat
section MEM cat /proc/meminfo
section NET cat /proc/net/dev
section TIME cat /proc/uptime
if [ "$TERMORA_DETAILS" = 1 ]; then
    section HOST hostname
    section SYSTEM uname -srmo
    section WHO who
    section DISKS df -PT -B1
    section DF df -hP
fi
