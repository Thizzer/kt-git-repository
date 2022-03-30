/**
 * This software may be modified and distributed under the terms
 * of the MIT license.  See the LICENSE file for details.
 */
import com.thizzer.git.Git
import com.thizzer.git.gitRepository
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.OffsetDateTime
import java.time.format.TextStyle
import java.util.*


class GitRepositoryTest {

    @Test
    fun singleBranchTest() {
        val repository = gitRepository {
            branch("main") {
                commit("Initial commit.") {
                    author("User", "noreply@example.com")

                    file("README.md", "This is a README.md file.")
                }
            }
        }

        Assertions.assertEquals(1, repository.refs().size)
    }

    @Test
    fun branchWithNameTest() {
        var mainBranch: Git.Branch? = null
        val repository = gitRepository {
            mainBranch = branch("main") {
                commit("Initial commit.") {
                    author("User", "noreply@example.com")

                    file("README.md", "This is a README.md file.")
                }
            }
        }

        val repoBranch = repository.getBranchWithName("main")
        Assertions.assertNotNull(repoBranch)
        Assertions.assertEquals(mainBranch, repoBranch)

        val nonExistingRepoBranch = repository.getBranchWithName("non-a-branch")
        Assertions.assertNull(nonExistingRepoBranch)
    }

    @Test
    fun commitWithTagTest() {
        var taggedCommit: Git.Commit? = null
        val repository = gitRepository {
            branch("main") {
                taggedCommit = commit("Initial commit.") {
                    author("User", "noreply@example.com")

                    tag("v1.0.0")

                    file("README.md", "This is a README.md file.")
                }
            }
        }

        val repoCommit = repository.getCommitWithTag("v1.0.0")
        Assertions.assertNotNull(repoCommit)
        Assertions.assertEquals(taggedCommit, repoCommit)

        val nonExistingRepoCommit = repository.getCommitWithTag("not-a-tag")
        Assertions.assertNull(nonExistingRepoCommit)
    }

    @Test
    fun depthTest() {
        val repository = gitRepository {
            branch("main") {
                commit("First commit.") {
                    author("User", "noreply@example.com")

                    file("README.md", "A.")
                }
                commit("Second commit.") {
                    author("User", "noreply@example.com")

                    file("README.md", "B.")
                }
                commit("Third commit.") {
                    author("User", "noreply@example.com")

                    file("README.md", "C.")
                }
            }
        }

        val mostRecentCommit = repository.getHead()?.getLastCommit()
        Assertions.assertNotNull(mostRecentCommit)
        Assertions.assertTrue(mostRecentCommit?.hasParent() == true)

        val mostRecentCommitHashList = listOf(mostRecentCommit!!.hash())

        val noSpecifiedDepthObjects = repository.byHashesWithChildren(mostRecentCommitHashList)
        Assertions.assertNotNull(noSpecifiedDepthObjects)
        Assertions.assertEquals(9, noSpecifiedDepthObjects.size)

        val twoDepthObjects = repository.byHashesWithChildren(mostRecentCommitHashList, depth = 2)
        Assertions.assertNotNull(twoDepthObjects)
        Assertions.assertEquals(6, twoDepthObjects.size)

        val oneDepthObjects = repository.byHashesWithChildren(mostRecentCommitHashList, depth = 1)
        Assertions.assertNotNull(oneDepthObjects)
        Assertions.assertEquals(3, oneDepthObjects.size)

        val fourDepthObjects = repository.byHashesWithChildren(mostRecentCommitHashList, depth = 4)
        Assertions.assertNotNull(fourDepthObjects)
        Assertions.assertEquals(9, fourDepthObjects.size)
    }
}