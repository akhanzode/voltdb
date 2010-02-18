#!/usr/bin/env python

from subprocess import Popen, PIPE
import os, sys, datetime, fcntl, time

DURATION_IN_SECONDS = 240

# make stdin non-blocking
fd = sys.stdin.fileno()
fl = fcntl.fcntl(fd, fcntl.F_GETFL)
fcntl.fcntl(fd, fcntl.F_SETFL, fl | os.O_NONBLOCK)

# compile the java code we need (assuming it's in the same folder)
print "Compiling Java Reprodcuer..."
output = os.system("javac LBDLockPatternTest.java")
if output == 0:
    print "Success"
else:
    print "Failed to compiler reproducer."
    print "Check the output of \"javac LBDLockPatternTest.java\" from your shell."
    sys.exit(-1)


def runTest(i):
    """Start a subprocess that runs the java reproducer. If it hangs, let the user know and
       leave the subprocess process running until the user presses a key. If it runs for
       DURATION_IN_SECONDS seconds without hanging, kill the subprocess and repeat."""

    print "\nBeginning run {0} for {1} seconds. Press ENTER or RETURN to end the test.\n".format(i, DURATION_IN_SECONDS)

    p = Popen("java LBDLockPatternTest", shell=True, bufsize=0, stdout=PIPE)

    # make the process's output non-blocking
    fd = p.stdout.fileno()
    fl = fcntl.fcntl(fd, fcntl.F_GETFL)
    fcntl.fcntl(fd, fcntl.F_SETFL, fl | os.O_NONBLOCK)

    # get the current time and init some variables
    start = datetime.datetime.utcnow()
    prevnow = start        # the last time a progress time was printed
    lastdotprinted = start # the last time a dot was successfully read

    while p.poll() == None:
        now = datetime.datetime.utcnow()

        # print a progress time out every 10 seconds
        if (now - prevnow).seconds == 10:
            prevnow = now
            sys.stdout.write(" {0} seconds ".format((now - start).seconds))

        # if no dots read in 10 seconds, then we assume the java proc has hung
        if (now - lastdotprinted).seconds > 10:
            print("\nSorry, this platfrom has reproduced the issue. Press any key to end this script.")
            raw_input()
            p.terminate()
            return False

        # if all's gone well for DURATION_IN_SECONDS, we kill the proc and return true
        if (now - start).seconds > DURATION_IN_SECONDS:
            print("\nThis run ({0}) did not reproduce the issue.".format(i))
            p.terminate()
            return True

        # do a non-blocking input read to see if the user wants to stop
        try:
            c = sys.stdin.read(1)
            print("\nThis run ({0}) interrupted by user.".format(i))
            p.terminate()
            sys.exit(-1)
        except:
            pass

        # do a non-blocking java-output read to see if a dot has been printed
        try:
            c = p.stdout.read(1)
            sys.stdout.write(c)
            lastdotprinted = now
        except:
            time.sleep(0.1)

# repeat until failure or the user presses ENTER or RETURN
i = 1
while runTest(i):
    i += 1