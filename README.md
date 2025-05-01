# Approov Service for OkHttp

A wrapper for the [Approov SDK](https://github.com/approov/approov-android-sdk) to enable easy integration when using [`OkHttp`](https://square.github.io/okhttp/) for making the API calls that you wish to protect with Approov. In order to use this you will need a trial or paid [Approov](https://www.approov.io) account.

Please see [Java](https://github.com/approov/quickstart-android-java-okhttp) or [Kotlin](https://github.com/approov/quickstart-android-kotlin-okhttp) quickstarts for usage information.

## Included 3rd party Source

To support message signing, this repo has adapted code released by two 3rd
party developers. The LICENSE files have been copied from the repos into the
associated directories listed below:

* `approov-service/src/main/java/io/approov/util/http/sfv`
    * Repo: https://github.com/reschke/structured-fields
    * Commit hash: d43f2ad6c655b92a7ef52aafa763418e1c6fed78
    * License: Apache V2
* `approov-service/src/main/java/io/approov/util/sig`
    * Repo: https://github.com/bspk/httpsig-java
    * Commit hash: ffe86ae1d07425f13b018329f51c7a7c0833d71f
    * License: MIT
