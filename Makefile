milestone := "wacc_examples/"

all: lint test build

lint:
	sbt scalafmtCheck

format:
	sbt scalafmt

test: build
	bash test.sh $(milestone)

check: build
	sbt test

build:
	sbt compile assembly 

clean:
	sbt clean && rm -rf wacc_examples/ wacc-33-compiler.jar test.log

.PHONY: all lint format test check build clean 
