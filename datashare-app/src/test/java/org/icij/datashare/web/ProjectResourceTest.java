package org.icij.datashare.web;

import net.codestory.http.filters.basic.BasicAuthFilter;
import net.codestory.http.security.Users;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.Repository;
import org.icij.datashare.cli.Mode;
import org.icij.datashare.session.DatashareUser;
import org.icij.datashare.session.LocalUserFilter;
import org.icij.datashare.session.YesBasicAuthFilter;
import org.icij.datashare.text.Project;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.user.User;
import org.icij.datashare.web.testhelpers.AbstractProdWebServerTest;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.*;

import static java.util.Arrays.asList;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

public class ProjectResourceTest extends AbstractProdWebServerTest {
    @Mock Repository repository;
    @Mock Indexer indexer;
    PropertiesProvider propertiesProvider;

    @Before
    public void setUp() {
        initMocks(this);
        configure(routes -> {
            propertiesProvider = new PropertiesProvider(new HashMap<String, String>() {{
                put("dataDir", "/vault");
                put("mode", "LOCAL");
            }});

            ProjectResource projectResource = new ProjectResource(repository, indexer, propertiesProvider);
            routes.filter(new LocalUserFilter(propertiesProvider)).add(projectResource);
        });
    }

    private Users get_datashare_users(String uid, List<String> groups) {
        User user = new User(new HashMap<String, Object>() {
                    {
                        this.put("uid", uid);
                        this.put("groups_by_applications", new HashMap<String, Object>() {
                            {
                                this.put("datashare", groups);
                            }
                        });
                    }
        });
        return DatashareUser.singleUser(user);
    }


    private Users get_datashare_users(List<String> groups) {
        return get_datashare_users("local", groups);
    }

    @Test
    public void test_get_project() {
        Project project = new Project("projectId");
        when(repository.getProject("projectId")).thenReturn(project);
        get("/api/project/projectId").should().respond(200)
                .contain("\"name\":\"projectId\"")
                .contain("\"label\":\"projectId\"");
    }

    @Test
    public void test_get_project_with_more_properties() {
        Project project = new Project(
                "projectId",
                "Project ID",
                Path.of("/vault/project"),
                "https://icij.org",
                "Data Team",
                "ICIJ",
                null,
                "*",
                new Date(),
                new Date());
        when(repository.getProject("projectId")).thenReturn(project);
        get("/api/project/projectId").should().respond(200)
                .contain("\"name\":\"projectId\"")
                .contain("\"label\":\"Project ID\"")
                .contain("\"sourceUrl\":\"https://icij.org\"")
                .contain("\"maintainerName\":\"Data Team\"")
                .contain("\"publisherName\":\"ICIJ\"")
                .contain("\"label\":\"Project ID\"");
    }

    @Test
    public void test_get_all_project_in_local_mode() {
        Project foo = new Project("foo");
        Project bar = new Project("bar");
        when(repository.getProjects(new String[]{"local-datashare", "foo", "bar"})).thenReturn(asList(foo, bar));
        when(repository.getProjects()).thenReturn(asList(foo, bar));
        get("/api/project/").should().respond(200)
                .contain("\"name\":\"foo\"")
                .contain("\"name\":\"bar\"");
    }

    @Test
    public void test_get_ony_user_project_in_server_mode() {
        configure(routes -> {
            PropertiesProvider propertiesProvider = new PropertiesProvider(new HashMap<String, String>() {{
                put("mode", Mode.SERVER.name());
            }});
            ProjectResource projectResource = new ProjectResource(repository, indexer, propertiesProvider);
            Users datashareUsers = get_datashare_users(asList("foo", "biz"));
            BasicAuthFilter basicAuthFilter = new BasicAuthFilter("/", "icij", datashareUsers);
            routes.filter(basicAuthFilter).add(projectResource);
        });

        Project foo = new Project("foo");
        Project bar = new Project("bar");
        when(repository.getProjects(new String[]{ "foo", "biz"})).thenReturn(List.of(foo));
        when(repository.getProjects()).thenReturn(asList(foo, bar));
        get("/api/project/")
                .withPreemptiveAuthentication("local", "")
                .should()
                    .respond(200)
                    .contain("\"name\":\"foo\"")
                    .not().contain("\"name\":\"bar\"");
    }

    @Test
    public void test_create_project_with_name_and_label() throws IOException {
        String body = "{ \"name\": \"foo-bar\", \"label\": \"Foo Bar\", \"sourcePath\": \"/vault/foo\" }";
        when(indexer.createIndex("foo-bar")).thenReturn(true);
        when(indexer.createIndex("local-datashare")).thenReturn(true);
        when(repository.save((Project) any())).thenReturn(true);
        post("/api/project/", body)
                .should()
                    .respond(201)
                    .contain("\"name\":\"foo-bar\"")
                    .contain("\"label\":\"Foo Bar\"");
    }

    @Test
    public void test_create_project() throws IOException {
        String body = "{ \"name\": \"foo\", \"label\": \"Foo v2\", \"sourcePath\": \"/vault/foo\", \"publisherName\":\"ICIJ\" }";
        when(indexer.createIndex("foo")).thenReturn(true);
        when(repository.getProject("foo")).thenReturn(null);
        when(repository.save((Project) any())).thenReturn(true);
        post("/api/project/", body).should().respond(201)
                .contain("\"name\":\"foo\"")
                .contain("\"label\":\"Foo v2\"")
                .contain("\"publisherName\":\"ICIJ\"")
                .contain("\"sourcePath\":\"file:///vault/foo\"");
    }
    @Test
    public void test_cannot_create_project_twice() throws IOException {
        String body = "{ \"name\": \"foo\", \"sourcePath\": \"/vault/foo\" }";
        when(indexer.createIndex("foo")).thenReturn(true);
        when(repository.save((Project) any())).thenReturn(true);
        when(repository.getProject("foo")).thenReturn(null);
        post("/api/project/", body).should().respond(201);
        when(repository.getProject("foo")).thenReturn(new Project("foo"));
        post("/api/project/", body).should().respond(409);
    }

    @Test
    public void test_cannot_create_project_without_source_path() {
        String body = "{ \"name\": \"projectId\", \"label\": \"Project ID\", \"sourceUrl\": \"https://icij.org\", \"publisherName\":\"ICIJ\" }";
        when(repository.getProject("foo")).thenReturn(null);
        post("/api/project/", body).should().respond(400);
    }

    @Test
    public void test_cannot_create_project_with_source_path_outside_data_dir() {
        String body = "{ \"name\": \"foo\", \"label\": \"Foo Bar\", \"sourcePath\": \"/home/foo\" }";
        when(repository.getProject("foo")).thenReturn(null);
        post("/api/project/", body).should().respond(400);
    }

    @Test
    public void test_can_create_project_with_source_path_using_data_dir() throws IOException {
        String body = "{ \"name\": \"foo\", \"label\": \"Foo Bar\", \"sourcePath\": \"/vault\" }";
        when(indexer.createIndex("foo")).thenReturn(true);
        when(repository.save((Project) any())).thenReturn(true);
        post("/api/project/", body).should().respond(201);
    }

    @Test
    public void test_cannot_create_project_without_name() {
        String body = "{ \"label\": \"Foo Bar\", \"sourcePath\": \"/vault/foo\" }";
        when(repository.getProject("foo")).thenReturn(null);
        post("/api/project/", body).should().respond(400);
    }

    @Test
    public void test_update_project() {
        when(repository.getProject("foo")).thenReturn(new Project("foo"));
        when(repository.save((Project) any())).thenReturn(true);
        String body = "{ \"name\": \"foo\", \"label\": \"Foo v3\" }";
        put("/api/project/foo", body).should().respond(200)
                .contain("\"name\":\"foo\"")
                .contain("\"label\":\"Foo v3\"");
    }

    @Test
    public void test_cannot_update_project_in_server_mode() {
        Properties props = new PropertiesProvider(Collections.singletonMap("mode", Mode.SERVER.name())).getProperties();
        propertiesProvider.overrideWith(props);
        when(repository.getProject("foo")).thenReturn(new Project("foo"));
        when(repository.save((Project) any())).thenReturn(true);
        String body = "{ \"name\": \"foo\" }";
        put("/api/project/foo", body).should().respond(403);
    }

    @Test
    public void test_is_allowed() {
        when(repository.getProject("projectId")).thenReturn(new Project("projectId", "127.0.0.1"));
        get("/api/project/isDownloadAllowed/projectId").should().respond(200);
    }

    @Test
    public void test_unknown_is_allowed() {
        get("/api/project/isDownloadAllowed/projectId").should().respond(200);
    }

    @Test
    public void test_is_not_allowed() {
        when(repository.getProject("projectId")).thenReturn(new Project("projectId", "127.0.0.2"));
        get("/api/project/isDownloadAllowed/projectId").should().respond(403);
    }

    @Test
    public void test_delete_project() throws SQLException {
        when(repository.deleteAll("local-datashare")).thenReturn(true).thenReturn(false);
        delete("/api/project/local-datashare").should().respond(204);
        delete("/api/project/local-datashare").should().respond(204);
    }

    @Test
    public void test_delete_project_only_delete_index() throws Exception {
        when(repository.deleteAll("local-datashare")).thenReturn(false).thenReturn(false);
        when(indexer.deleteAll("local-datashare")).thenReturn(true).thenReturn(false);
        delete("/api/project/local-datashare").should().respond(204);
        delete("/api/project/local-datashare").should().respond(204);
    }

    @Test
    public void test_delete_project_with_unauthorized_user() {
        configure(routes -> {
            PropertiesProvider propertiesProvider = new PropertiesProvider(Collections.singletonMap("mode", Mode.SERVER.name()));
            routes.filter(new YesBasicAuthFilter(propertiesProvider))
                    .add(new ProjectResource(repository, indexer, propertiesProvider));
        });
        when(repository.deleteAll("hacker-datashare")).thenReturn(true);
        when(repository.deleteAll("projectId")).thenReturn(true);
        delete("/api/project/hacker-datashare").withPreemptiveAuthentication("hacker", "pass").should().respond(403);
        delete("/api/project/projectId").should().respond(401);
    }
}
