#!/bin/zsh

## set variables/constants required by the script

# The version of the package that will be build and will be visible in maven central
# For Approov SDK release 3.3.0 (library 7257) the version was 3.3.0
# This is also used to rename the folder where the package is stored by replacing the TAG-RENAME-DIR
# THE POM FILE MUST BE UPDATED WITH THE CORRECT VERSION WHICH MUST MATCH THIS VARIABLE
VERSION="3.3.0"

# Constant: current package name
CURRENT_PACKAGE_NAME="service-okhttp"

# Constant: Required package subdir structure
PACKAGE_DIR_STRUCTURE="io/approov/${CURRENT_PACKAGE_NAME}"

# Constant: The file prefix for each file placed in the above directory
# NOTE: This is also the name of the binary SDK file expected by Maven
FILE_PREFIX="${CURRENT_PACKAGE_NAME}-${VERSION}"

# The PGP Key ID to use for signing the package; set by CI/CD
# export PGP_KEY_ID=""

# Password for the GPG key; set by CI/CD
# export GPG_PASSWORD=""


# The full path to the service aar package generated by gradle build.
AAR_PATH="../approov-service/build/outputs/aar/approov-service-release.aar"


# The path to the javadoc.jar file that will be uploaded to maven central
JAVADOC_JAR_PATH="../docs/javadoc.jar"

# Path to the POM file: YOU MUST UPDATE THIS FILE WITH THE CORRECT <version
# which MUST match the VERSION variable above
POM_FILE_PATH="../pom.xml"

# Check if the above files exist before proceeding further
if [ ! -f ${AAR_PATH} ]; then
    echo "File not found: ${AAR_PATH}"
    echo "Please make sure the file exists or change the location in the script and try again"
    exit 1
fi

if [ ! -f ${JAVADOC_JAR_PATH} ]; then
    echo "File not found: ${JAVADOC_JAR_PATH}"
    echo "Please make sure the file exists or change the location in the script and try again"
    exit 1
fi

if [ ! -f ${POM_FILE_PATH} ]; then
    echo "File not found: ${POM_FILE_PATH}"
    echo "Please make sure the file exists or change the location in the script and try again"
    exit 1
fi


# The destination directory to place all the files
DESTINATION_DIR="${PACKAGE_DIR_STRUCTURE}/${VERSION}"

echo "Will create destination directory: ${DESTINATION_DIR}"
# Create destination directory in current location
mkdir -p ${DESTINATION_DIR}
# Check if the command was successful
if [ $? -eq 0 ]; then
    echo "File successfully created: ${DESTINATION_DIR}"
else
    echo "Failed to create directory ${DESTINATION_DIR}"
    exit 1
fi

# Copy operations to destination directory
# 1. Copy javadoc.jar file and rename to destination:
# Maven expects for version 3.2.2 of the javadoc.jar the following file
# approov-service-3.2.2-javadoc.jar
cp ${JAVADOC_JAR_PATH} ${DESTINATION_DIR}/${FILE_PREFIX}-javadoc.jar

# Check if the command was successful
if [ $? -eq 0 ]; then
    echo "File successfully copied: ${DESTINATION_DIR}/${FILE_PREFIX}-javadoc.jar"
else
    echo "Failed to copy file as ${DESTINATION_DIR}/${FILE_PREFIX}-javadoc.jar"
    exit 1
fi

# Sign the target javadoc file
OUTPUT_SIGNATURE="${DESTINATION_DIR}/${FILE_PREFIX}-javadoc.jar.asc"
gpg --batch --yes --passphrase "$GPG_PASSWORD" --pinentry-mode loopback --output "$OUTPUT_SIGNATURE" --detach-sign --local-user "$PGP_KEY_ID" "${DESTINATION_DIR}/${FILE_PREFIX}-javadoc.jar"

# Check if the command was successful
if [ $? -eq 0 ]; then
    echo "File successfully signed: ${DESTINATION_DIR}/${FILE_PREFIX}-javadoc.jar.asc"
else
    echo "Failed to sign file as ${DESTINATION_DIR}/${FILE_PREFIX}-javadoc.jar.asc"
    exit 1
fi
# Compute hashes for the javadoc file
OUTPUT_FILE="${DESTINATION_DIR}/${FILE_PREFIX}-javadoc.jar.sha1"
# Compute SHA-1 and extract only the hash
shasum "${DESTINATION_DIR}/${FILE_PREFIX}-javadoc.jar" | awk '{print $1}' > "$OUTPUT_FILE"
# sha256
OUTPUT_FILE="${DESTINATION_DIR}/${FILE_PREFIX}-javadoc.jar.sha256"
shasum -a 256 "${DESTINATION_DIR}/${FILE_PREFIX}-javadoc.jar" | awk '{print $1}' > "$OUTPUT_FILE"
# sha512
OUTPUT_FILE="${DESTINATION_DIR}/${FILE_PREFIX}-javadoc.jar.sha512"
shasum -a 512 "${DESTINATION_DIR}/${FILE_PREFIX}-javadoc.jar" | awk '{print $1}' > "$OUTPUT_FILE"
# md5
OUTPUT_FILE="${DESTINATION_DIR}/${FILE_PREFIX}-javadoc.jar.md5"
md5 "${DESTINATION_DIR}/${FILE_PREFIX}-javadoc.jar" | awk '{print $4}' > "$OUTPUT_FILE"


# 2. Copy the aar file and rename to destination:
# Maven expects for version 3.2.2 of the aar file the following file
# service-okhttp-3.2.2.aar
cp ${AAR_PATH} ${DESTINATION_DIR}/${FILE_PREFIX}.aar

# Check if the command was successful
if [ $? -eq 0 ]; then
    echo "File successfully copied: ${DESTINATION_DIR}/${FILE_PREFIX}.aar"
else
    echo "Failed to copy file as ${DESTINATION_DIR}/${FILE_PREFIX}.aar"
    exit 1
fi

# Sign the android SDK aar file
OUTPUT_SIGNATURE="${DESTINATION_DIR}/${FILE_PREFIX}.aar.asc"
gpg --batch --yes --passphrase "$GPG_PASSWORD" --pinentry-mode loopback --output "$OUTPUT_SIGNATURE" --detach-sign --local-user "$PGP_KEY_ID" "${DESTINATION_DIR}/${FILE_PREFIX}.aar"

# Check if the command was successful
if [ $? -eq 0 ]; then
    echo "File successfully signed: ${DESTINATION_DIR}/${FILE_PREFIX}.aar.asc"
else
    echo "Failed to sign file as ${DESTINATION_DIR}/${FILE_PREFIX}.aar.asc"
    exit 1
fi

# Compute hashes for the aar file
OUTPUT_FILE="${DESTINATION_DIR}/${FILE_PREFIX}.aar.sha1"
# Compute SHA-1 and extract only the hash
shasum "${DESTINATION_DIR}/${FILE_PREFIX}.aar" | awk '{print $1}' > "$OUTPUT_FILE"
# sha256
OUTPUT_FILE="${DESTINATION_DIR}/${FILE_PREFIX}.aar.sha256"
shasum -a 256 "${DESTINATION_DIR}/${FILE_PREFIX}.aar" | awk '{print $1}' > "$OUTPUT_FILE"
# sha512
OUTPUT_FILE="${DESTINATION_DIR}/${FILE_PREFIX}.aar.sha512"
shasum -a 512 "${DESTINATION_DIR}/${FILE_PREFIX}.aar" | awk '{print $1}' > "$OUTPUT_FILE"
# md5
OUTPUT_FILE="${DESTINATION_DIR}/${FILE_PREFIX}.aar.md5"
md5 "${DESTINATION_DIR}/${FILE_PREFIX}.aar" | awk '{print $4}' > "$OUTPUT_FILE"

# 3. Copy the pom file and rename to destination:
# Maven expects for version 3.2.2 of the pom file the following file
# service-okhttp-3.2.2.pom
cp ${POM_FILE_PATH} ${DESTINATION_DIR}/${FILE_PREFIX}.pom

# Check if the command was successful
if [ $? -eq 0 ]; then
    echo "File successfully copied: ${DESTINATION_DIR}/${FILE_PREFIX}.pom"
else
    echo "Failed to copy file as ${DESTINATION_DIR}/${FILE_PREFIX}.pom"
    exit 1
fi

# Sign the pom file
OUTPUT_SIGNATURE="${DESTINATION_DIR}/${FILE_PREFIX}.pom.asc"
gpg --batch --yes --passphrase "$GPG_PASSWORD" --pinentry-mode loopback --output "$OUTPUT_SIGNATURE" --detach-sign --local-user "$PGP_KEY_ID" "${DESTINATION_DIR}/${FILE_PREFIX}.pom"

# Check if the command was successful
if [ $? -eq 0 ]; then
    echo "File successfully signed: ${DESTINATION_DIR}/${FILE_PREFIX}.pom.asc"
else
    echo "Failed to sign file as ${DESTINATION_DIR}/${FILE_PREFIX}.pom.asc"
    exit 1
fi

# Compute hashes for the pom file
OUTPUT_FILE="${DESTINATION_DIR}/${FILE_PREFIX}.pom.sha1"
# Compute SHA-1 and extract only the hash
shasum "${DESTINATION_DIR}/${FILE_PREFIX}.pom" | awk '{print $1}' > "$OUTPUT_FILE"
# sha256
OUTPUT_FILE="${DESTINATION_DIR}/${FILE_PREFIX}.pom.sha256"
shasum -a 256 "${DESTINATION_DIR}/${FILE_PREFIX}.pom" | awk '{print $1}' > "$OUTPUT_FILE"
# sha512
OUTPUT_FILE="${DESTINATION_DIR}/${FILE_PREFIX}.pom.sha512"
shasum -a 512 "${DESTINATION_DIR}/${FILE_PREFIX}.pom" | awk '{print $1}' > "$OUTPUT_FILE"
# md5
OUTPUT_FILE="${DESTINATION_DIR}/${FILE_PREFIX}.pom.md5"
md5 "${DESTINATION_DIR}/${FILE_PREFIX}.pom" | awk '{print $4}' > "$OUTPUT_FILE"

# Force remove recursively all the .DS_Store files that might have been copied
find "io/" -name ".DS_Store" -type f -delete
# Finally zip the io/ directory and save it in current directory as ${FILE_PREFIX}.zip
zip -r ${FILE_PREFIX}.zip "io"