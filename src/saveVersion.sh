#!/bin/sh

# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.


# This file is used to generate the BuildStamp.java class that
# records the user, url, revision and timestamp.
version=$1
revision=$HADOOP_REVISION
#if [ -z "$revision" ] ; then
#  revision=`svn info | sed -n -e 's/Last Changed Rev: \(.*\)/\1/p'`
#fi
if [ -z "$revision" ]  ; then
  revision=`git rev-parse HEAD`
fi
#url=`svn info | sed -n -e 's/URL: \(.*\)/\1/p'`
url=`git config --list | sed -n -e 's/remote.origin.url=\(.*\)/\1/p'`
user=`whoami`
date=`date`
mkdir -p src/java/com/cloudera/flume
cat << EOF | \
  sed -e "s/VERSION/$version/" -e "s/USER/$user/" -e "s/DATE/$date/" \
      -e "s|URL|$url|" -e "s/REV/$revision/" \
      > src/java/com/cloudera/flume/package-info.java
/**
 * Licensed to Cloudera, Inc. under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  Cloudera, Inc. licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/*
 * Generated by src/saveVersion.sh
 */
@FlumeVersionAnnotation(version="VERSION", revision="REV", 
                         user="USER", date="DATE", url="URL")
package com.cloudera.flume;
EOF
