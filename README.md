# Kotlin Git Repository

Kotlin library for generating Git object content.

Note: To be used as actual object- and packfiles, the generated content has to be compressed.

## Installation

### Maven

```xml
<dependency>
	<groupId>com.thizzer.kt-git-repository</groupId>
	<artifactId>kt-git-repository</artifactId>
	<version>1.1.0</version>
</dependency>
```

### Gradle

#### Groovy

```gradle
implementation group: 'com.thizzer.kt-git-repository', name: 'kt-git-repository', version: '1.1.0'
```

#### Kotlin

```kotlin
implementation("com.thizzer.kt-git-repository:kt-git-repository:1.1.0")
```

## Usage

```kotlin
val repository = gitRepository {
    branch("main") {
        commit("Initial commit.") {
            file("README.md", "New and shiny README.md file.")
        }
    }
}
```