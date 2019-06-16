
def asssertMagicFileExists(filename) {
    File magicFile = new File( basedir, filename )
    assert magicFile.exists()
}

def asssertMagicFileNotExists(filename) {
    File magicFile = new File( basedir, filename )
    assert !magicFile.exists()
}

asssertMagicFileExists('/target/parent-infrastructure_opensource.md')
asssertMagicFileNotExists('/target/parent-infrastructure_opensource-nexus2-staging.md')
asssertMagicFileExists('/target/parent-java8-profile1.md')
asssertMagicFileExists('/target/parent-java-8-profile2.md')
asssertMagicFileExists('/target/parent-profile-sonar.md')
asssertMagicFileExists('/build-docker/target/build-docker-java8-profile1.md')
asssertMagicFileExists('/build-docker/target/build-docker-java-8-profile2.md')
