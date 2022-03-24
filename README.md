# Kotlin Git Repository

Kotlin library for generating Git object content.

Note: To be used as actual object- and packfiles, the generated content has to be compressed.

## Example

```kotlin
val repository = gitRepository {
    branch("main") {
        commit("Initial commit.") {
            file("README.md", "New and shiny README.md file.")
        }
    }
}
```