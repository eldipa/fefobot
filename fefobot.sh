#rm -rf sockets-2023c2-abraida/
#Example: ./fefobot.sh abraida sockets
echo "Cloning $2 repo from student $1. 2024c1"
gh repo clone Taller-de-Programacion-TPs/$2-2023c2-$1
cd $2-2023c2-$1
# delete any non source code file recursively
find . -type f -not -name '*.cpp' -a -not -name '*.h' -a -not -name '.' -delete
# delete directories that may have emptied after deleting files
find . -type d -empty -delete

joern --nocolors < ../joern_commands.txt > ./output.txt
