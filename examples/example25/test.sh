#!/bin/bash -e

cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null

../test.sh cs1302/banking/Driver.java -a -b=29
