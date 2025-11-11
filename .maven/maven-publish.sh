#!/bin/bash

## set variables/constants required by the script
# The current tag of github's branch
# Bail out if CURRENT_TAG is not set
if [ -z "$CURRENT_TAG" ]; then
  echo "Error: CURRENT_TAG is not set. This script requires a tag to be set."
  exit 1
fi

# Check the MAVEN_USERNAME and MAVEN_PASSWORD are set
if [ -z "$MAVEN_USERNAME" ]; then
  echo "Error: MAVEN_USERNAME is not set. This script requires a username to be set."
  exit 1
fi

if [ -z "$MAVEN_PASSWORD" ]; then
  echo "Error: MAVEN_PASSWORD is not set. This script requires a password to be set."
  exit 1
fi

# The body artifact name
BODY_ARTIFACT="service.okhttp-${CURRENT_TAG}.zip"

# The username:password for the maven repository
MAVEN_CREDENTIALS=$(printf "${MAVEN_USERNAME}:${MAVEN_PASSWORD}" | base64)
# Publish the body artifact
curl --request POST \
  --verbose \
  --header "Authorization: Bearer ${MAVEN_CREDENTIALS}" \
  --form "bundle=@${BODY_ARTIFACT}" \
  "https://central.sonatype.com/api/v1/publisher/upload?publishingType=AUTOMATED&name=service.okhttp"
