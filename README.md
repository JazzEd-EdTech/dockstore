[![CircleCI](https://circleci.com/gh/dockstore/dockstore.svg?style=svg)](https://circleci.com/gh/dockstore/dockstore)
[![Language grade: Java](https://img.shields.io/lgtm/grade/java/g/dockstore/dockstore.svg?logo=lgtm&logoWidth=18)](https://lgtm.com/projects/g/dockstore/dockstore/context:java)
[![codecov](https://codecov.io/gh/dockstore/dockstore/branch/develop/graph/badge.svg)](https://codecov.io/gh/dockstore/dockstore)
[![Website](https://img.shields.io/website/https/dockstore.org.svg)](https://dockstore.org)

[![DOI](https://zenodo.org/badge/DOI/10.5281/zenodo.5781764.svg)](https://doi.org/10.5281/zenodo.5781764)
[![Uptime Robot status](https://img.shields.io/uptimerobot/status/m779655940-a297af07d1cac2d6ad40c491.svg)]()
[![license](https://img.shields.io/hexpm/l/plug.svg?maxAge=2592000)](LICENSE)
[![Documentation Status](https://readthedocs.org/projects/dockstore/badge/?version=stable)](https://dockstore.readthedocs.io/en/stable/?badge=stable)


# Dockstore

Dockstore provides a place for users to share tools encapsulated in Docker and described with the Common 
Workflow Language (CWL), WDL (Workflow Description Language), Nextflow, or Galaxy. This enables scientists to share analytical 
workflows so that they are  machine readable as well as runnable in a variety of environments. While the 
Dockstore is focused on serving researchers in the biosciences, the combination of Docker + workflow languages can be used by 
anyone to describe the tools and services in their Docker images in a standardized, machine-readable way.  
Dockstore is also a leading implementor of the GA4GH API standard for container registries, [TRS](https://www.ga4gh.org/news/tool-registry-service-api-enabling-an-interoperable-library-of-genomics-analysis-tools/).

For the live site see [dockstore.org](https://dockstore.org)

This repo contains the web service and CLI components for Dockstore as well as collecting documentation and 
the issues for the project as a whole. The usage of this is to enumerate the docker containers 
(from quay.io and hopefully docker hub) and the workflows (from github/bitbucket) that are available 
to users of Dockstore.org.

For the related web UI see the [dockstore-ui](https://github.com/dockstore/dockstore-ui2) project.

## For Dockstore Users

The following section is useful for users of Dockstore (e.g. those that want to browse, register, and 
launch tools). 

After registering at [dockstore.org](https://dockstore.org), you will be able to download the Dockstore 
CLI at https://dockstore.org/onboarding

### Configuration File

A basic Dockstore configuration file is available/should be created in `~/.dockstore/config` and contains the following
at minimum:
```
token = <your token generated by the dockstore site>
server-url = https://www.dockstore.org/api
```

### Migration to Dockstore 1.7

1. Ensure that you are using Java 11. Java 8 (both Open and Oracle) will not work.


### File Provisioning

By default, cwltool reads input files from the local filesystem. Dockstore also adds support for additional file systems
such as http, https, and ftp. Through a plug-in system, Dockstore also supports 
the Amazon S3 and [Synapse](http://docs.synapse.org/articles/downloading_data.html) via [plugins](https://github.com/dockstore).

Download the above set of default plugins via: 
```
dockstore plugin download
```

Configuration for plugins can be placed inside the Dockstore configuration file in the following format

```
token = <your generated by the dockstore site>
server-url = https://www.dockstore.org/api

# options below this are optional

use-cache = false                           #set this to true to cache input files for rapid development
cache-dir = /home/<user>/.dockstore/cache   #set this to determine where input files are cached (should be the same filesystem as your tool working directories)

[dockstore-file-synapse-plugin]

[dockstore-file-s3-plugin]
endpoint = #set this to point at a non AWS S3 endpoint

[dockstore-file-icgc-storage-client-plugin]
client = /media/large_volume/icgc-storage-client-1.0.23/bin/icgc-storage-client
```

Additional plugins can be created by taking one of the repos in [plugins](https://github.com/dockstore) as a model and 
using [pf4j](https://github.com/decebals/pf4j) as a reference. See [additional documentation](https://github.com/dockstore/dockstore-cli/tree/develop/dockstore-file-plugin-parent) for more details. 

## For Dockstore Developers

The following section is useful for Dockstore developers (e.g. those that want to improve or fix the Dockstore web service and UI)

### Dependencies

The dependency environment for Dockstore is described by our 
[CircleCI config](https://github.com/dockstore/dockstore/blob/develop/.circleci/config.yml) or [docker compose](docker-compose.yml). In addition to the dependencies for 
Dockstore users, note the setup instructions for postgres. Specifically, you will need to have postgres installed 
and setup with the database user specified in [.circleci/config.yml](https://github.com/dockstore/dockstore/blob/1.11.10/.circleci/config.yml#L279) (ideally, postgres is needed only for integration tests but not unit tests).

### Building

As an alternative to the following commands, if you do not have Maven installed you can use the maven wrapper as a substitute. For example:

    ./mvnw clean install
    # instead of
    mvn clean install

If you maven build in the root directory this will build all modules:

    mvn clean install
    # or
    mvn clean install -Punit-tests
    
Consider the following if you need to build a specific version (such as in preparation for creating a tag for a release):

    mvnw clean install  -Dchangelist=.0-beta.5 #or whatever version you need 
    
If you're running tests on CircleCI (or otherwise have access to the confidential data bundle) Run them via:

    mvn clean install -Pintegration-tests
    
There are also certain categories for tests that they can be added to when writing new tests. 
Categories include:

1. `ToilCompatibleTest` are tests that can be run with our default cwltool and with Toil
2. `ConfidentialTest` are tests that require access to our confidential testing bundle (ask a member of the development team if you're on the team)

### Running Locally

You can also run it on your local computer but will need to setup postgres separately.

1. Fill in the template dockstore.yml and stash it somewhere outside the git repo (like ~/.dockstore)
2. The dockstore.yml is mostly a standard [Dropwizard configuration file](https://www.dropwizard.io/en/release-2.0.x/manual/configuration.html). 
Refer to the linked document to setup httpClient and database. 
3. Start with `java -jar dockstore-webservice/target/dockstore-webservice-*.jar   server ~/.dockstore/dockstore.yml`
4. If you need integration with GitHub.com, Quay.io. or Bitbucket for your work, you will need to follow the appropriate 
sections below and then fill out the corresponding fields in your 
[dockstore.yml](https://github.com/dockstore/dockstore/blob/develop/dockstore-integration-testing/src/test/resources/dockstore.yml). 

One alternative if you prefer running things in containers would be using [docker-compose](docker-compose.yml)

### View Swagger UI

The Swagger UI is reachable while the Dockstore webservice is running. This allows you to explore available web resources.

1. Browse to [http://localhost:8080/static/swagger-ui/index.html](http://localhost:8080/static/swagger-ui/index.html)


## Development

### Coding Standards

[codestyle.xml](codestyle.xml) defines the coding style for Dockstore as an IntelliJ Code Style XML file that should be imported into IntelliJ IDE. 
We also have a matching [checkstyle.xml](checkstyle.xml) that can be imported into other IDEs and is run during the build.  

For users of Intellij or comparable IDEs, we also suggest loading the checkstyle.xml with a plugin in order to display warnings and errors while coding live rather than encountering them later when running a build. 

#### Installing git-secrets

Dockstore uses git-secrets to help make sure that keys and private data stay out
of the source tree. For information on installing it on your platform check <https://github.com/awslabs/git-secrets#id6> .

If you're on mac with homebrew use `brew install git-secrets`.

### Dockstore Command Line

The dockstore command line should be installed in a location in your path.

  /dockstore-client/bin/dockstore

You then need to setup a `~/.dockstore/config` file with the following contents:

```
token: <dockstore_token_from_web_app>
server-url: http://www.dockstore.org:8080
```

If you are working with a custom-built or updated dockstore client you will need to update the jar in: `~/.dockstore/config/self-installs`.

### Swagger Client Generation 

We use the swagger-codegen-maven-plugin to generate several sections of code which are not checked in. 
These include
1. All of swagger-java-client (talks to our webservice for the CLI via Swagger 2.0)
2. All of openapi-java-client (talks to our webservice for the CLI, but in OpenAPI 3.0)
3. The Tool Registry Server components (serves up the TRS endpoints)

To update these, you will need to point at a new version of the swagger.yaml provided by a service. For example, update the equivalent of [inputSpec](https://github.com/dockstore/dockstore/blob/0afe35682bdfb6fa7285b2acab8f80648346e835/dockstore-webservice/pom.xml#L854) in your branch.  

### Encrypted Documents for CircleCI

Encrypted documents necessary for confidential testing are decrypted via [decrypt.sh](scripts/decrypt.sh) with access being granted to developers at UCSC and OICR.

A convenience script is provided as [encrypt.sh](encrypt.sh) which will compress confidential files, encrypt them, and then update an encrypted archive on GitHub. Confidential files should also be added to .gitignore to prevent accidental check-in. The unencrypted secrets.tar should be privately distributed among members of the team that need to work with confidential data. When using this script you will likely want to alter the [CUSTOM\_DIR\_NAME](https://github.com/dockstore/dockstore/blob/0b59791440af6e3d383d1aede1774c0675b50404/encrypt.sh#L13). This is necessary since running the script will overwrite the existing encryption keys, instantly breaking existing builds using that key. Our current workaround is to use a new directory when providing a new bundle. 

### Adding Copyright header to all files with IntelliJ

To add copyright headers to all files with IntelliJ

1. Ensure the Copyright plugin is installed (Settings -> Plugins)
2. Create a new copyright profile matching existing copyright header found on all files, name it Dockstore (Settings -> Copyright -> Copyright Profiles -> Add New)
3. Set the default project copyright to Dockstore (Settings -> Copyright)

### Setting up a Mac for Dockstore development
Install Docker (Be sure to click on 'Mac with Apple chip' if you have Apple silicon)
https://docs.docker.com/desktop/mac/install/

Install Brew
https://brew.sh/
```
/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"
```

Run 'git' to trigger the install of Xcode or the Command Line Tools which will install and or update git
https://developer.apple.com/forums/thread/672087?answerId=659036022#659036022
```
git
```
_(If that doesn't work install git manually https://git-scm.com/download/mac)_


Setup git user information
```
git config --global user.email "you@example.com"
git config --global user.name "Your Name"
```
[Read about git token requirements](https://github.blog/2020-12-15-token-authentication-requirements-for-git-operations/)

[Setup personal access token for git CLI](https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/creating-a-personal-access-token)

[It's helpful to cache your git personal access token](https://docs.github.com/en/get-started/getting-started-with-git/caching-your-github-credentials-in-git)

Install Hubflow
https://datasift.github.io/gitflow/TheHubFlowTools.html
```
git clone https://github.com/datasift/gitflow
cd gitflow
sudo ./install.sh
```

Install JDK 17
https://formulae.brew.sh/formula/openjdk@17
```
brew install openjdk@17
```
Download and install node.js
https://github.com/nvm-sh/nvm
```
curl -o- https://raw.githubusercontent.com/nvm-sh/nvm/v0.39.1/install.sh | bash
```
Install git secrets
https://github.com/awslabs/git-secrets
```
brew install git-secrets
```
Install wget
```
brew install wget
```

Install jq
```
brew install jq
```
#### Build the webservice
(cd to where you cloned the dockstore/dockstore repo)
```
./mvnw clean install
```

#### Build the UI
(cd to where you cloned the dockstore/dockstore-ui2 repo)

Set up UI requirements
NOTE: You must use the --legacy-peer-deps switch due to using npm version 8.11.0 (> npm 6) 
for reasons mentioned in [this post](https://stackoverflow.com/questions/66239691/what-does-npm-install-legacy-peer-deps-do-exactly-when-is-it-recommended-wh)
```
npm ci --legacy-peer-deps
```
Run prebuild
```
npm run prebuild
```

Run build
```
npm run build
```
#### Optional
Install IntelliJ _(if on Apple Silicon, select the .dmg (Apple Silicon), otherwise select .dmg(Intel)_

https://www.jetbrains.com/idea/download/#section=mac

Add the Scala plugin to IntelliJ
https://www.jetbrains.com/help/idea/managing-plugins.html



### Legacy Material

Additional documentation on developing Dockstore is available at [legacy.md](https://github.com/dockstore/dockstore/blob/develop/legacy.md)
