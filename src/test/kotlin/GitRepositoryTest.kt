/**
 * This software may be modified and distributed under the terms
 * of the MIT license.  See the LICENSE file for details.
 */
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
}