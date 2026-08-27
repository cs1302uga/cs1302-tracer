.PHONY: all clean compile test package install ci help

# Default target
all: package

# Clean build artifacts
clean:
	mvn clean

# Compile source files
compile:
	mvn compile

# Run tests and verify coverage
test:
	mvn test

# Package fat JAR and source bundle
package:
	mvn package

# Install artifacts to local repository
install:
	mvn install

# Run GitHub Actions workflow locally using act
ci:
	act --container-architecture linux/amd64 -j test

help:
	@echo "Available targets:"
	@echo "  make ci       - Run GitHub Actions CI test matrix locally using act"
	@echo "  make test     - Run JUnit tests with JaCoCo coverage check"
	@echo "  make package  - Build executable fat JAR and source bundle"
	@echo "  make install  - Install JAR and POM to local Maven repository"
	@echo "  make clean    - Remove build target directory"
