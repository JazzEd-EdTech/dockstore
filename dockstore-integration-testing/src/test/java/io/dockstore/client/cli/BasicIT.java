/*
 *    Copyright 2017 OICR
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package io.dockstore.client.cli;

import static junit.framework.TestCase.fail;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import io.dockstore.common.CommonTestUtilities;
import io.dockstore.common.ConfidentialTest;
import io.dockstore.common.DescriptorLanguage;
import io.dockstore.common.Registry;
import io.dockstore.common.SlowTest;
import io.dockstore.common.SourceControl;
import io.dockstore.common.ToolTest;
import io.dockstore.openapi.client.model.SourceFile;
import io.dockstore.webservice.core.Entry;
import io.dockstore.webservice.resources.EventSearchType;
import io.swagger.client.ApiClient;
import io.swagger.client.ApiException;
import io.swagger.client.api.ContainersApi;
import io.swagger.client.api.ContainertagsApi;
import io.swagger.client.api.EventsApi;
import io.swagger.client.api.UsersApi;
import io.swagger.client.api.WorkflowsApi;
import io.swagger.client.model.DockstoreTool;
import io.swagger.client.model.Event;
import io.swagger.client.model.Event.TypeEnum;
import io.swagger.client.model.StarRequest;
import io.swagger.client.model.Tag;
import io.swagger.client.model.User;
import io.swagger.client.model.Workflow;
import io.swagger.model.DescriptorType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import org.eclipse.jetty.http.HttpStatus;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.contrib.java.lang.system.ExpectedSystemExit;
import org.junit.contrib.java.lang.system.SystemErrRule;
import org.junit.contrib.java.lang.system.SystemOutRule;
import org.junit.experimental.categories.Category;

/**
 * Basic confidential integration tests, focusing on publishing/unpublishing both automatic and manually added tools
 * This is important as it tests the web service with real data instead of dummy data, using actual services like Github and Quay
 *
 * @author aduncan
 */
@Category({ ConfidentialTest.class, ToolTest.class })
public class BasicIT extends BaseIT {
    @Rule
    public final ExpectedSystemExit systemExit = ExpectedSystemExit.none();

    @Rule
    public final SystemOutRule systemOutRule = new SystemOutRule().enableLog().muteForSuccessfulTests();

    @Rule
    public final SystemErrRule systemErrRule = new SystemErrRule().enableLog().muteForSuccessfulTests();

    @Before
    @Override
    public void resetDBBetweenTests() throws Exception {
        CommonTestUtilities.cleanStatePrivate1(SUPPORT);
    }


    /*
     * General-ish tests
     */

    @Test
    public void testDisallowedOrgRefresh() {
        ApiClient client = getWebClient(USER_1_USERNAME, testingPostgres);
        UsersApi usersApi = new UsersApi(client);
        try {
            usersApi.refreshToolsByOrganization((long)1, "DockstoreTestUser", null);
            fail("Refresh by organization should fail");
        } catch (ApiException e) {
            assertTrue("Should see error message", e.getMessage().contains("Missing the required parameter"));
        }
    }

    /**
     * Tests that registration works with non-short names
     */
    @Test
    public void testRegistrationWithNonLowerCase() {
        ApiClient client = getWebClient(USER_1_USERNAME, testingPostgres);
        WorkflowsApi workflowsApi = new WorkflowsApi(client);

        workflowsApi.manualRegister(SourceControl.GITHUB.name(), "DockstoreTestUser/dockstore-whalesay-wdl", "/dockstore.wdl", "", DescriptorLanguage.WDL.getShortName(), "");

        // refresh a specific workflow
        Workflow workflow = workflowsApi
                .getWorkflowByPath(SourceControl.GITHUB.toString() + "/DockstoreTestUser/dockstore-whalesay-wdl", BIOWORKFLOW, "");
        workflow = workflowsApi.refresh(workflow.getId(), false);
        assertFalse(workflow.getWorkflowVersions().isEmpty());
    }

    @Test
    public void testRefreshToolNoVersions() {
        ApiClient client = getWebClient(USER_1_USERNAME, testingPostgres);
        ContainersApi containersApi = new ContainersApi(client);
        DockstoreTool tool = containersApi.getContainerByToolPath("quay.io/dockstoretestuser/noautobuild", null);
        tool.setGitUrl("git@github.com:DockstoreTestUser/dockstore-whalesay.git");
        containersApi.updateContainer(tool.getId(), tool);
        containersApi.refresh(tool.getId());


        tool = containersApi.getContainerByToolPath("quay.io/dockstoretestuser/nobuildsatall", null);
        tool.setGitUrl("git@github.com:DockstoreTestUser/dockstore-whalesay.git");
        containersApi.updateContainer(tool.getId(), tool);
        DockstoreTool refresh = containersApi.refresh(tool.getId());
        assertNull(refresh.getDefaultVersion());
    }


    /**
     * Tests that refresh workflows works, also that refreshing without a github token should not destroy workflows or their existing versions
     */
    @Test
    public void testRefreshWorkflow() {
        ApiClient client = getWebClient(USER_1_USERNAME, testingPostgres);
        WorkflowsApi workflowsApi = new WorkflowsApi(client);

        workflowsApi.manualRegister(SourceControl.GITHUB.name(), "DockstoreTestUser/dockstore-whalesay-wdl", "/dockstore.wdl", "", DescriptorLanguage.WDL.getShortName(), "");
        workflowsApi.manualRegister(SourceControl.GITHUB.name(), "DockstoreTestUser/ampa-nf", "/nextflow.config", "", DescriptorLanguage.NEXTFLOW.getShortName(), "");


        // should have a certain number of workflows based on github contents
        final long secondWorkflowCount = testingPostgres.runSelectStatement("select count(*) from workflow", long.class);
        assertTrue("should find non-zero number of workflows", secondWorkflowCount > 0);

        // refresh a specific workflow
        Workflow workflow = workflowsApi
            .getWorkflowByPath(SourceControl.GITHUB.toString() + "/DockstoreTestUser/dockstore-whalesay-wdl", BIOWORKFLOW, "");
        workflow = workflowsApi.refresh(workflow.getId(), false);

        // artificially create an invalid version
        testingPostgres.runUpdateStatement("update workflowversion set name = 'test'");
        testingPostgres.runUpdateStatement("update workflowversion set reference = 'test'");

        // refresh individual workflow
        workflow = workflowsApi.refresh(workflow.getId(), false);

        // check that the version was deleted
        final long updatedWorkflowVersionCount = testingPostgres.runSelectStatement("select count(*) from workflowversion", long.class);
        final long updatedWorkflowVersionName = testingPostgres
            .runSelectStatement("select count(*) from workflowversion where name='master'", long.class);
        assertTrue("there should be only one version", updatedWorkflowVersionCount == 1 && updatedWorkflowVersionName == 1);

        // delete quay.io token
        testingPostgres.runUpdateStatement("delete from token where tokensource = 'github.com'");

        systemExit.checkAssertionAfterwards(() -> {
            // should not delete workflows
            final long thirdWorkflowCount = testingPostgres.runSelectStatement("select count(*) from workflow", long.class);
            Assert.assertEquals("there should be no change in count of workflows", secondWorkflowCount, thirdWorkflowCount);
        });

        // should include nextflow example workflow stub
        final long nfWorkflowCount = testingPostgres
            .runSelectStatement("select count(*) from workflow where giturl like '%ampa-nf%'", long.class);
        assertTrue("should find non-zero number of next flow workflows", nfWorkflowCount > 0);

        // refresh without github token
        try {
            workflow = workflowsApi.refresh(workflow.getId(), false);
        } catch (ApiException e) {
            assertTrue(e.getMessage().contains("No GitHub or Google token found"));
        }
    }

    /**
     * Tests manually adding, updating, and removing a dockerhub tool
     */
    @Test
    public void testVersionTagDockerhub() {
        ApiClient client = getWebClient(USER_1_USERNAME, testingPostgres);
        ContainersApi toolsApi = new ContainersApi(client);
        ContainertagsApi toolTagsApi = new ContainertagsApi(client);

        DockstoreTool tool = manualRegisterAndPublish(toolsApi, "dockstoretestuser", "dockerhubandgithub", "regular",
            "git@github.com:DockstoreTestUser/dockstore-whalesay.git", "/Dockstore.cwl", "/Dockstore.wdl", "/Dockerfile",
            DockstoreTool.RegistryEnum.DOCKER_HUB, "master", "latest", true);
        EventsApi eventsApi = new EventsApi(client);
        List<Event> events = eventsApi.getEvents(EventSearchType.STARRED_ENTRIES.toString(), 10, 0);
        Assert.assertTrue("No starred entries, so there should be no events returned", events.isEmpty());
        StarRequest starRequest = new StarRequest();
        starRequest.setStar(true);
        toolsApi.starEntry(tool.getId(), starRequest);
        events = eventsApi.getEvents(EventSearchType.STARRED_ENTRIES.toString(), 10, 0).stream()
            .filter(e -> e.getType() != TypeEnum.PUBLISH_ENTRY).collect(Collectors.toList());
        Assert.assertTrue("Should not be an event for the non-tag version that was automatically created for the newly registered tool", events.isEmpty());
        // Add a tag
        Tag tag = new Tag();
        tag.setName("masterTest");
        tag.setReference("master");
        tag.setReferenceType(Tag.ReferenceTypeEnum.TAG);
        tag.setImageId("4728f8f5ce1709ec8b8a5282e274e63de3c67b95f03a519191e6ea675c5d34e8");
        List<Tag> tags = new ArrayList<>();
        tags.add(tag);

        tags = toolTagsApi.addTags(tool.getId(), tags);
        events = eventsApi.getEvents(EventSearchType.STARRED_ENTRIES.toString(), 10, 0).stream()
            .filter(e -> e.getType() != TypeEnum.PUBLISH_ENTRY).collect(Collectors.toList());
        Assert.assertEquals("Should have created an event for the new tag", 1, events.size());
        final long count = testingPostgres.runSelectStatement("select count(*) from tag where name = 'masterTest'", long.class);
        Assert.assertEquals("there should be one tag", 1, count);

        // Update tag
        Optional<Tag> optTag = tags.stream().filter(version -> Objects.equals(version.getName(), "masterTest")).findFirst();
        if (optTag.isEmpty()) {
            fail("Should have masterTest tag");
        }
        tag = optTag.get();
        tag.setHidden(true);
        tags = new ArrayList<>();
        tags.add(tag);
        toolTagsApi.updateTags(tool.getId(), tags);
        toolsApi.refresh(tool.getId());

        final long count2 = testingPostgres.runSelectStatement(
            "select count(*) from tag t, version_metadata vm where name = 'masterTest' and vm.hidden='t' and t.id = vm.id", long.class);
        Assert.assertEquals("there should be one tag", 1, count2);

        toolTagsApi.deleteTags(tool.getId(), tag.getId());

        final long count3 = testingPostgres.runSelectStatement("select count(*) from tag where name = 'masterTest'", long.class);
        Assert.assertEquals("there should be no tags", 0, count3);

    }
    @Test
    public void testRecentEventsByUser() {

        // Create API client for user
        ApiClient client = getWebClient(USER_1_USERNAME, testingPostgres);

        UsersApi usersApi = new UsersApi(client);
        User user = usersApi.getUser();
        ContainersApi toolsApi = new ContainersApi(client);

        // Register a tool
        DockstoreTool tool = manualRegisterAndPublish(toolsApi, "dockstoretestuser", "dockerhubandgithub", "regular",
            "git@github.com:DockstoreTestUser/dockstore-whalesay.git", "/Dockstore.cwl", "/Dockstore.wdl", "/Dockerfile",
            DockstoreTool.RegistryEnum.DOCKER_HUB, "master", "latest", true);

        // Nothing has been starred so the events API should return an empty collection for the user
        EventsApi eventsApi = new EventsApi(client);
        List<Event> events = eventsApi.getEvents(EventSearchType.STARRED_ENTRIES.toString(), 10, 0);
        Assert.assertTrue("No starred entries, so there should be no events returned", events.isEmpty());

        // Star the tool that was registered above
        StarRequest starRequest = new StarRequest();
        starRequest.setStar(true);
        toolsApi.starEntry(tool.getId(), starRequest);

        // Events API should return 1 event
        events = eventsApi.getEvents(EventSearchType.STARRED_ENTRIES.toString(), 10, 0);
        assertEquals("The user should return 1 starred entry", 1, events.size());

        // Get the event to compare with another user's request
        Event event = events.get(0);

        // Create a second client and query for the starred event from the first user
        ApiClient client2 = getWebClient(USER_2_USERNAME, testingPostgres);
        EventsApi client2EventsApi = new EventsApi(client2);

        // Get events by user id
        List<Event> eventsForFirstClient = client2EventsApi.getUserEvents(user.getId(), EventSearchType.STARRED_ENTRIES.toString(), 10, 0);
        assertEquals("The user should return 1 starred entry", 1, eventsForFirstClient.size());

        // Get user initiated events by user id
        List<Event> profileEventsForFirstClient = client2EventsApi.getUserEvents(user.getId(), EventSearchType.PROFILE.toString(), 10, 0);
        assertTrue("The user events should be all intiiated by the client", !profileEventsForFirstClient.isEmpty() && profileEventsForFirstClient.stream().allMatch(e -> Objects.equals(e.getInitiatorUser().getId(), user.getId())));

        // Get the identified event
        Event event2 = eventsForFirstClient.get(0);

        // Assert the event client2 was able to identify is the same event caused by the first client
        assertEquals("The two events should have the same ID", event.getId(), event2.getId());
    }

    @Test
    public void testRecentEventsByUserWithNullInput() {

        // Create a second client and query for the starred event from the first user
        ApiClient client = getWebClient(USER_2_USERNAME, testingPostgres);
        EventsApi eventsApi = new EventsApi(client);

        // This should throw an error because no user exists with ID -1
        try {
            List<Event> events = eventsApi.getUserEvents(-1L, EventSearchType.STARRED_ENTRIES.toString(), 10, 0);
            fail("No user exists with ID -1");
        } catch (ApiException e) {
            assertTrue(e.getMessage().contains("User not found."));

        }
    }

    /**
     * Tests the case where a manually registered quay tool matching an automated build should be treated as a separate auto build (see issue 106)
     */
    @Test
    public void testManualQuaySameAsAutoQuay() {
        ApiClient client = getWebClient(USER_1_USERNAME, testingPostgres);
        ContainersApi toolsApi = new ContainersApi(client);


        DockstoreTool tool = manualRegisterAndPublish(toolsApi, "dockstoretestuser", "quayandgithub", "regular",
            "git@github.com:DockstoreTestUser/dockstore-whalesay.git", "/Dockstore.cwl", "/Dockstore.wdl", "/Dockerfile",
            DockstoreTool.RegistryEnum.QUAY_IO, "master", "latest", true);

        final long count = testingPostgres.runSelectStatement(
            "select count(*) from tool where mode != 'MANUAL_IMAGE_PATH' and registry = '" + Registry.QUAY_IO.getDockerPath()
                + "' and namespace = 'dockstoretestuser' and name = 'quayandgithub' and toolname = 'regular'", long.class);
        Assert.assertEquals("the tool should be Auto", 1, count);
    }

    /**
     * Tests the case where a manually registered quay tool has the same path as an auto build but different git repo
     */
    @Test
    public void testManualQuayToAutoSamePathDifferentGitRepo() {
        ApiClient client = getWebClient(USER_1_USERNAME, testingPostgres);
        ContainersApi toolsApi = new ContainersApi(client);

        DockstoreTool tool = manualRegisterAndPublish(toolsApi, "dockstoretestuser", "quayandgithub", "alternate",
            "git@github.com:DockstoreTestUser/dockstore-whalesay-alternate.git", "/testDir/Dockstore.cwl", "/testDir/Dockstore.wdl",
            "/testDir/Dockerfile", DockstoreTool.RegistryEnum.QUAY_IO, "master", "latest", true);

        final long count = testingPostgres.runSelectStatement(
            "select count(*) from tool where mode = 'MANUAL_IMAGE_PATH' and registry = '" + Registry.QUAY_IO.getDockerPath()
                + "' and namespace = 'dockstoretestuser' and name = 'quayandgithub' and toolname = 'alternate'", long.class);
        Assert.assertEquals("the tool should be Manual still", 1, count);
    }

    /**
     * Tests that a manually published tool still becomes manual even after the existing similar auto tools all have toolnames (see issue 120)
     */
    @Test
    public void testManualQuayToAutoNoAutoWithoutToolname() {
        ApiClient client = getWebClient(USER_1_USERNAME, testingPostgres);
        ContainersApi toolsApi = new ContainersApi(client);

        DockstoreTool existingTool = toolsApi.getContainerByToolPath("quay.io/dockstoretestuser/quayandgithub", "");
        existingTool.setToolname("testToolname");
        toolsApi.updateContainer(existingTool.getId(), existingTool);

        DockstoreTool tool = manualRegisterAndPublish(toolsApi, "dockstoretestuser", "quayandgithub", "testtool",
            "git@github.com:DockstoreTestUser/dockstore-whalesay.git", "/Dockstore.cwl", "/Dockstore.wdl", "/Dockerfile",
            DockstoreTool.RegistryEnum.QUAY_IO, "master", "latest", true);

        final long count = testingPostgres.runSelectStatement(
            "select count(*) from tool where mode != 'MANUAL_IMAGE_PATH' and registry = '" + Registry.QUAY_IO.getDockerPath()
                + "' and namespace = 'dockstoretestuser' and name = 'quayandgithub' and toolname = 'testtool'", long.class);
        Assert.assertEquals("the tool should be Auto", 1, count);
    }

    /**
     * Tests the case where a manually registered quay tool does not have any automated builds set up, though a manual build was run (see issue 107)
     * UPDATE: Should fail because you can't publish a tool with no valid tags
     */
    @Test
    public void testManualQuayManualBuild() {
        ApiClient client = getWebClient(USER_1_USERNAME, testingPostgres);
        ContainersApi toolsApi = new ContainersApi(client);

        try {
            DockstoreTool tool = manualRegisterAndPublish(toolsApi, "dockstoretestuser", "noautobuild", "alternate",
                "git@github.com:DockstoreTestUser/dockstore-whalesay.git", "/Dockstore.cwl", "/Dockstore.wdl", "/Dockerfile",
                DockstoreTool.RegistryEnum.QUAY_IO, "master", "latest", true);
            fail("Should not be able to publish");
        } catch (ApiException e) {
            assertTrue(e.getMessage().contains("Repository does not meet requirements to publish."));
        }
    }

    /**
     * Tests the case where a manually registered quay tool does not have any tags
     */
    @Test
    public void testManualQuayNoTags() {
        ApiClient client = getWebClient(USER_1_USERNAME, testingPostgres);
        ContainersApi toolsApi = new ContainersApi(client);

        try {
            DockstoreTool tool = manualRegisterAndPublish(toolsApi, "dockstoretestuser", "nobuildsatall", "alternate",
                "git@github.com:DockstoreTestUser/dockstore-whalesay.git", "/Dockstore.cwl", "/Dockstore.wdl", "/Dockerfile",
                DockstoreTool.RegistryEnum.QUAY_IO, "master", "latest", true);
            fail("Should not be able to register");
        } catch (ApiException e) {
            assertTrue(e.getMessage().contains("has no tags."));
        }
    }

    /**
     * Tests that a quick registered quay tool with no autobuild can be updated to have a manually set CWL file from git (see issue 19)
     */
    @Test
    public void testQuayNoAutobuild() {
        ApiClient client = getWebClient(USER_1_USERNAME, testingPostgres);
        ContainersApi toolsApi = new ContainersApi(client);

        DockstoreTool existingTool = toolsApi.getContainerByToolPath("quay.io/dockstoretestuser/noautobuild", "");
        existingTool.setGitUrl("git@github.com:DockstoreTestUser/dockstore-whalesay.git");
        existingTool = toolsApi.updateContainer(existingTool.getId(), existingTool);

        final long count = testingPostgres.runSelectStatement("select count(*) from tool where registry = '" + Registry.QUAY_IO.getDockerPath()
                + "' and namespace = 'dockstoretestuser' and name = 'noautobuild' and giturl = 'git@github.com:DockstoreTestUser/dockstore-whalesay.git'",
            long.class);
        Assert.assertEquals("the tool should now have an associated git repo", 1, count);

        DockstoreTool existingToolNoBuild = toolsApi.getContainerByToolPath("quay.io/dockstoretestuser/nobuildsatall", "");
        existingToolNoBuild.setGitUrl("git@github.com:DockstoreTestUser/dockstore-whalesay.git");
        existingToolNoBuild = toolsApi.updateContainer(existingToolNoBuild.getId(), existingToolNoBuild);

        final long count2 = testingPostgres.runSelectStatement("select count(*) from tool where registry = '" + Registry.QUAY_IO.getDockerPath()
                + "' and namespace = 'dockstoretestuser' and name = 'nobuildsatall' and giturl = 'git@github.com:DockstoreTestUser/dockstore-whalesay.git'",
            long.class);
        Assert.assertEquals("the tool should now have an associated git repo", 1, count2);

    }

    /**
     * Tests a user trying to add a quay tool that they do not own and are not in the owning organization
     */
    @Test
    public void testAddQuayRepoOfNonOwnedOrg() {
        // Repo user isn't part of org
        ApiClient client = getWebClient(USER_1_USERNAME, testingPostgres);
        ContainersApi toolsApi = new ContainersApi(client);

        try {
            DockstoreTool tool = manualRegisterAndPublish(toolsApi, "dockstore2", "testrepo2", "testOrg",
                "git@github.com:DockstoreTestUser/dockstore-whalesay.git", "/Dockstore.cwl", "/Dockstore.wdl", "/Dockerfile",
                DockstoreTool.RegistryEnum.QUAY_IO, "master", "latest", true);
            fail("Should not be able to register");
        } catch (ApiException e) {
            assertTrue(e.getMessage().contains("User does not own"));
        }
    }

    /**
     * TODO: Don't use SQL statements here
     * The testing database originally has tools with tags.  This test:
     * - Deletes a tag from a certain tool from db
     * - Refreshes the tool
     * - Checks if the tag is back
     */
    @Test
    public void testRefreshAfterDeletingAVersion() {
        // Get the tool id of the entry whose path is quay.io/dockstoretestuser/quayandgithub
        final long id = testingPostgres
            .runSelectStatement("select id from tool where name = 'quayandgithub' and namespace='dockstoretestuser' and registry='quay.io'",
                long.class);

        // Check how many versions the entry has
        final long currentNumberOfTags = testingPostgres
            .runSelectStatement("select count(*) from tag where parentid = '" + id + "'", long.class);
        assertTrue("There are no tags for this tool", currentNumberOfTags > 0);

        // This grabs the first tag that belongs to the tool
        final long firstTag = testingPostgres.runSelectStatement("select id from tag where parentid = '" + id + "'", long.class);

        // Delete the version that is known
        testingPostgres.runUpdateStatement("delete from tag where parentid = '" + id + "' and id='" + firstTag + "'");

        // Double check that there is one less tag
        final long afterDeletionTags = testingPostgres
            .runSelectStatement("select count(*) from tag where parentid = '" + id + "'", long.class);
        Assert.assertEquals(currentNumberOfTags - 1, afterDeletionTags);

        // Refresh the tool
        ApiClient client = getWebClient(USER_1_USERNAME, testingPostgres);
        ContainersApi toolsApi = new ContainersApi(client);
        DockstoreTool tool = toolsApi.getContainerByToolPath("quay.io/dockstoretestuser/quayandgithub", "");
        toolsApi.refresh(tool.getId());

        // Check how many tags there are after the refresh
        final long afterRefreshTags = testingPostgres
            .runSelectStatement("select count(*) from tag where parentid = '" + id + "'", long.class);
        Assert.assertEquals(currentNumberOfTags, afterRefreshTags);
    }

    /**
     * Tests that a git reference for a tool can include branches named like feature/...
     */
    @Test
    public void testGitReferenceFeatureBranch() {
        final long count = testingPostgres.runSelectStatement("select count(*) from tag where reference = 'feature/test'", long.class);
        Assert.assertEquals("there should be 2 tags with the reference feature/test", 2, count);
    }

    /**
     * This tests that a tool's default version can be automatically set during refresh
     */
    @Test
    public void testUpdateToolDefaultVersionDuringRefresh() {
        ApiClient client = getWebClient(USER_1_USERNAME, testingPostgres);
        ContainersApi toolsApi = new ContainersApi(client);
        DockstoreTool tool = manualRegisterAndPublish(toolsApi, "dockstoretestuser", "quayandgithub", "regular",
                "git@github.com:DockstoreTestUser/dockstore-whalesay.git", "/Dockstore.cwl", "/Dockstore.wdl", "/Dockerfile",
                DockstoreTool.RegistryEnum.QUAY_IO, "master", "latest", true);
        Assert.assertEquals("manualRegisterAndPublish does a refresh, it should automatically set the default version", "latest", tool.getDefaultVersion());
        tool = toolsApi.updateToolDefaultVersion(tool.getId(), "test");
        Assert.assertEquals("Should be able to overwrite previous default version", "test", tool.getDefaultVersion());
        tool = toolsApi.refresh(tool.getId());
        Assert.assertEquals("Refresh should not have set it back to the automatic one", "test", tool.getDefaultVersion());

    }

    /**
     * Tests that a WDL file is supported
     */
    @Test
    public void testQuayGithubQuickRegisterWithWDL() {
        ApiClient client = getWebClient(USER_1_USERNAME, testingPostgres);
        ContainersApi toolsApi = new ContainersApi(client);
        DockstoreTool tool = toolsApi.getContainerByToolPath("quay.io/dockstoretestuser/quayandgithub", "");
        tool = toolsApi.refresh(tool.getId());
        tool = toolsApi.publish(tool.getId(), CommonTestUtilities.createPublishRequest(true));
        final long count = testingPostgres.runSelectStatement("select count(*) from tool where registry = '" + Registry.QUAY_IO.getDockerPath()
            + "' and namespace = 'dockstoretestuser' and name = 'quayandgithub' and ispublished = 't'", long.class);
        Assert.assertEquals("the given entry should be published", 1, count);
    }


    /**
     * This tests that a tool can be updated to have default version, and that metadata is set related to the default version
     */
    @Test
    public void testSetDefaultTag() {
        ApiClient client = getWebClient(USER_1_USERNAME, testingPostgres);
        ContainersApi toolsApi = new ContainersApi(client);
        // Update tool with default version that has metadata
        DockstoreTool existingTool = toolsApi.getContainerByToolPath("quay.io/dockstoretestuser/quayandgithub", "");
        Long toolId = existingTool.getId();
        DockstoreTool refresh = toolsApi.refresh(toolId);
        refresh.setDefaultVersion("master");
        existingTool = toolsApi.updateContainer(toolId, refresh);

        final long count = testingPostgres.runSelectStatement("select count(*) from tool where registry = '" + Registry.QUAY_IO.getDockerPath()
            + "' and namespace = 'dockstoretestuser' and name = 'quayandgithub' and actualdefaultversion is not null", long.class);
        Assert.assertEquals("the tool should have a default version set", 1, count);

        final long count2 = testingPostgres.runSelectStatement("select count(*) from tool where registry = '" + Registry.QUAY_IO.getDockerPath()
                + "' and namespace = 'dockstoretestuser' and name = 'quayandgithub' and actualdefaultversion is not null and author = 'Dockstore Test User'",
            long.class);
        Assert.assertEquals("the tool should have any metadata set (author)", 1, count2);

        // Invalidate tags
        testingPostgres.runUpdateStatement("UPDATE tag SET valid='f'");

        // Shouldn't be able to publish
        try {
            toolsApi.publish(toolId, CommonTestUtilities.createPublishRequest(true));
            fail("Should not be able to publish");
        } catch (ApiException e) {
            assertTrue(e.getMessage().contains("Repository does not meet requirements to publish."));
        }

    }

    /**
     * This tests that a tool cannot be manually published if it has no default descriptor paths
     * Also tests for entry not found when a broken path is used
     */
    @Test
    public void testManualPublishToolNoDescriptorPaths() {
        // Manual publish, should fail
        ApiClient client = getWebClient(USER_1_USERNAME, testingPostgres);
        ContainersApi toolsApi = new ContainersApi(client);

        // Manual publish
        try {
            DockstoreTool tool = manualRegisterAndPublish(toolsApi, "dockstoretestuser", "quayandgithubalternate", "alternate",
                "git@github.com:DockstoreTestUser/dockstore-whalesay-alternate.git", "", "", "/testDir/Dockerfile",
                DockstoreTool.RegistryEnum.DOCKER_HUB, "master", "latest", true);
            fail("Should not be able to publish");
        } catch (ApiException e) {
            assertTrue(e.getMessage().contains("Repository does not meet requirements to publish"));
        }
        testBrokenPath();
    }

    public void testBrokenPath() {
        ApiClient client = getWebClient(USER_1_USERNAME, testingPostgres);
        WorkflowsApi workflowsApi = new WorkflowsApi(client);
        try {
            workflowsApi.getWorkflowByPath("potato", BIOWORKFLOW, "potato");
            Assert.fail("Should've not been able to get an entry that does not exist");
        } catch (ApiException e) {
            Assert.assertEquals("Entry not found", e.getMessage());
        }
    }

    /**
     * This tests the dirty bit attribute for tool tags with quay
     */
    @Test
    public void testQuayDirtyBit() {
        ApiClient client = getWebClient(USER_1_USERNAME, testingPostgres);
        ContainersApi toolsApi = new ContainersApi(client);
        ContainertagsApi toolTagsApi = new ContainertagsApi(client);

        // Check that no tags have a true dirty bit
        final long count = testingPostgres.runSelectStatement("select count(*) from tag where dirtybit = true", long.class);
        Assert.assertEquals("there should be no tags with dirty bit, there are " + count, 0, count);

        // Edit tag cwl
        DockstoreTool existingTool = toolsApi.getContainerByToolPath("quay.io/dockstoretestuser/quayandgithub", "");
        Optional<Tag> optTag = existingTool.getWorkflowVersions().stream().filter(version -> Objects.equals(version.getName(), "master"))
            .findFirst();
        if (optTag.isEmpty()) {
            fail("There should exist a master tag");
        }
        Tag tag = optTag.get();
        tag.setCwlPath("/Dockstoredirty.cwl");
        List<Tag> tags = new ArrayList<>();
        tags.add(tag);
        toolTagsApi.updateTags(existingTool.getId(), tags);

        // Edit another tag wdl
        optTag = existingTool.getWorkflowVersions().stream().filter(version -> Objects.equals(version.getName(), "latest")).findFirst();
        if (optTag.isEmpty()) {
            fail("There should exist a master tag");
        }
        tag = optTag.get();
        tag.setWdlPath("/Dockstoredirty.wdl");
        tags = new ArrayList<>();
        tags.add(tag);
        toolTagsApi.updateTags(existingTool.getId(), tags);

        // There should now be two true dirty bits
        final long count1 = testingPostgres.runSelectStatement("select count(*) from tag where dirtybit = true", long.class);
        Assert.assertEquals("there should be two tags with dirty bit, there are " + count1, 2, count1);

        // Update default cwl to /Dockstoreclean.cwl
        existingTool = toolsApi.getContainerByToolPath("quay.io/dockstoretestuser/quayandgithub", "");
        existingTool.setDefaultCwlPath("/Dockstoreclean.cwl");
        existingTool = toolsApi.updateContainer(existingTool.getId(), existingTool);
        toolsApi.refresh(existingTool.getId());

        // There should only be one tag with /Dockstoreclean.cwl (both tag with new cwl and new wdl should be dirty and not changed)
        final long count2 = testingPostgres
            .runSelectStatement("select count(*) from tag where cwlpath = '/Dockstoreclean.cwl'", long.class);
        Assert.assertEquals("there should be only one tag with the cwl path /Dockstoreclean.cwl, there are " + count2, 1, count2);
    }

    /**
     * This tests basic concepts with tool test parameter files
     */
    @Test
    public void testTestJson() {
        ApiClient client = getWebClient(USER_1_USERNAME, testingPostgres);
        ContainersApi toolsApi = new ContainersApi(client);
        ContainertagsApi toolTagsApi = new ContainertagsApi(client);

        // Refresh
        DockstoreTool existingTool = toolsApi.getContainerByToolPath("quay.io/dockstoretestuser/test_input_json", "");
        toolsApi.refresh(existingTool.getId());

        // Check that no WDL or CWL test files
        final long count = testingPostgres.runSelectStatement("select count(*) from sourcefile where type like '%_TEST_JSON'", long.class);
        Assert.assertEquals("there should be no sourcefiles that are test parameter files, there are " + count, 0, count);

        // Update tag with test parameters
        List<String> toAdd = new ArrayList<>();
        toAdd.add("test.cwl.json");
        toAdd.add("test2.cwl.json");
        toAdd.add("fake.cwl.json");

        List<String> toRemove = new ArrayList<>();
        toRemove.add("notreal.cwl.json");

        toolsApi.addTestParameterFiles(existingTool.getId(), toAdd, "cwl", "", "master");
        try {
            toolsApi.deleteTestParameterFiles(existingTool.getId(), toRemove, "cwl", "master");
            Assert.fail("Should've have thrown an error when deleting non-existent file");
        } catch (ApiException e) {
            assertEquals("Should have returned a 404 when deleting non-existent file", HttpStatus.NOT_FOUND_404, e.getCode());
        }
        toolsApi.refresh(existingTool.getId());

        final long count2 = testingPostgres.runSelectStatement("select count(*) from sourcefile where type like '%_TEST_JSON'", long.class);
        Assert.assertEquals("there should be two sourcefiles that are test parameter files, there are " + count2, 2, count2);

        // Update tag with test parameters
        toAdd = new ArrayList<>();
        toAdd.add("test.cwl.json");

        toRemove = new ArrayList<>();
        toRemove.add("test2.cwl.json");

        toolsApi.addTestParameterFiles(existingTool.getId(), toAdd, "cwl", "", "master");
        toolsApi.deleteTestParameterFiles(existingTool.getId(), toRemove, "cwl", "master");
        toolsApi.refresh(existingTool.getId());

        final long count3 = testingPostgres.runSelectStatement("select count(*) from sourcefile where type like '%_TEST_JSON'", long.class);
        Assert.assertEquals("there should be one sourcefile that is a test parameter file, there are " + count3, 1, count3);

        // Update tag wdltest with test parameters
        toAdd = new ArrayList<>();
        toAdd.add("test.wdl.json");

        toolsApi.addTestParameterFiles(existingTool.getId(), toAdd, "wdl", "", "wdltest");
        toolsApi.refresh(existingTool.getId());

        final long count4 = testingPostgres.runSelectStatement("select count(*) from sourcefile where type='WDL_TEST_JSON'", long.class);
        Assert.assertEquals("there should be one sourcefile that is a wdl test parameter file, there are " + count4, 1, count4);

        toAdd = new ArrayList<>();
        toAdd.add("test.cwl.json");

        toolsApi.addTestParameterFiles(existingTool.getId(), toAdd, "cwl", "", "wdltest");
        toolsApi.refresh(existingTool.getId());
        final long count5 = testingPostgres.runSelectStatement("select count(*) from sourcefile where type='CWL_TEST_JSON'", long.class);
        assertEquals("there should be two sourcefiles that are test parameter files, there are " + count5, 2, count5);

        // refreshing again with the default paths set should not create extra redundant test parameter files
        existingTool = toolsApi.getContainerByToolPath("quay.io/dockstoretestuser/test_input_json", "");
        existingTool.setDefaultCWLTestParameterFile("test.cwl.json");
        existingTool.setDefaultWDLTestParameterFile("test.wdl.json");
        toolsApi.updateContainer(existingTool.getId(), existingTool);
        toolsApi.refresh(existingTool.getId());
        final List<Long> testJsonCounts = testingPostgres.runSelectListStatement(
            "select count(*) from sourcefile s, version_sourcefile vs where (s.type = 'CWL_TEST_JSON' or s.type = 'WDL_TEST_JSON') and s.id = vs.sourcefileid group by vs.versionid",
            long.class);
        assertTrue("there should be at least three sets of test json sourcefiles " + testJsonCounts.size(), testJsonCounts.size() >= 3);
        for (Long testJsonCount : testJsonCounts) {
            assertTrue("there should be at most two test json for each version", testJsonCount <= 2);
        }
    }

    @Test
    public void testTestParameterOtherUsers() {
        final ApiClient correctWebClient = getWebClient(BaseIT.USER_1_USERNAME, testingPostgres);
        final ApiClient otherWebClient = getWebClient(BaseIT.OTHER_USERNAME, testingPostgres);

        ContainersApi containersApi = new ContainersApi(correctWebClient);
        final DockstoreTool containerByToolPath = containersApi.getContainerByToolPath("quay.io/dockstoretestuser/test_input_json", null);
        containersApi.refresh(containerByToolPath.getId());

        // Check that no WDL or CWL test files
        final long count = testingPostgres.runSelectStatement("select count(*) from sourcefile where type like '%_TEST_JSON'", long.class);
        Assert.assertEquals("there should be no sourcefiles that are test parameter files, there are " + count, 0, count);

        containersApi
            .addTestParameterFiles(containerByToolPath.getId(), Collections.singletonList("/test.json"), DescriptorType.CWL.toString(), "",
                "master");

        boolean shouldFail = false;
        try {
            final ContainersApi containersApi1 = new ContainersApi(otherWebClient);
            containersApi1.addTestParameterFiles(containerByToolPath.getId(), Collections.singletonList("/test2.cwl.json"),
                DescriptorType.CWL.toString(), "", "master");
        } catch (Exception e) {
            shouldFail = true;
        }
        assertTrue(shouldFail);

        containersApi
            .addTestParameterFiles(containerByToolPath.getId(), Collections.singletonList("/test2.cwl.json"), DescriptorType.CWL.toString(),
                "", "master");

        final long count3 = testingPostgres.runSelectStatement("select count(*) from sourcefile where type like '%_TEST_JSON'", long.class);
        Assert.assertEquals("there should be one sourcefile that is a test parameter file, there are " + count3, 2, count3);

        // start testing deletion
        shouldFail = false;
        try {
            final ContainersApi containersApi1 = new ContainersApi(otherWebClient);
            containersApi1.deleteTestParameterFiles(containerByToolPath.getId(), Collections.singletonList("/test2.cwl.json"),
                DescriptorType.CWL.toString(), "master");
        } catch (Exception e) {
            shouldFail = true;
        }
        assertTrue(shouldFail);
        containersApi
            .deleteTestParameterFiles(containerByToolPath.getId(), Collections.singletonList("/test.json"), DescriptorType.CWL.toString(),
                "master");
        containersApi.deleteTestParameterFiles(containerByToolPath.getId(), Collections.singletonList("/test2.cwl.json"),
            DescriptorType.CWL.toString(), "master");

        final long count4 = testingPostgres.runSelectStatement("select count(*) from sourcefile where type like '%_TEST_JSON'", long.class);
        Assert.assertEquals("there should be one sourcefile that is a test parameter file, there are " + count4, 0, count4);
    }

    /**
     * This tests some cases for private tools
     */
    @Test
    public void testPrivateManualPublish() {
        ApiClient client = getWebClient(USER_1_USERNAME, testingPostgres);
        ContainersApi toolsApi = new ContainersApi(client);

        // Manual publish private repo with tool maintainer email
        DockstoreTool tool = manualRegisterAndPublish(toolsApi, "dockstoretestuser", "private_test_repo", "tool1",
            "git@github.com:DockstoreTestUser/dockstore-whalesay.git", "/Dockstore.cwl", "/Dockstore.wdl", "/Dockerfile",
            DockstoreTool.RegistryEnum.DOCKER_HUB, "master", "latest", true, true, "testemail@domain.com", null);

        // The tool should be private, published and have the correct email
        final long count = testingPostgres.runSelectStatement(
            "select count(*) from tool where ispublished='true' and privateaccess='true' and toolmaintaineremail='testemail@domain.com'",
            long.class);
        Assert.assertEquals("one tool should be private and published, there are " + count, 1, count);

        // Manual publish public repo
        DockstoreTool publicTool = manualRegisterAndPublish(toolsApi, "dockstoretestuser", "private_test_repo", "tool2",
            "git@github.com:DockstoreTestUser/dockstore-whalesay.git", "/Dockstore.cwl", "/Dockstore.wdl", "/Dockerfile",
            DockstoreTool.RegistryEnum.DOCKER_HUB, "master", "latest", true);

        // NOTE: The tool should not have an associated email

        // Should not be able to convert to a private repo since it is published and has no email
        long toolId = publicTool.getId();
        publicTool.setPrivateAccess(true);
        try {
            publicTool = toolsApi.updateContainer(publicTool.getId(), publicTool);
            fail("Should not be able to update without email");
        } catch (ApiException e) {
            assertTrue(
                e.getMessage().contains("A published, private tool must have either an tool author email or tool maintainer email set up"));
        }

        // Give the tool a tool maintainer email
        publicTool = toolsApi.getContainer(toolId, "");
        publicTool.setPrivateAccess(true);
        publicTool.setToolMaintainerEmail("testemail@domain.com");
        publicTool = toolsApi.updateContainer(publicTool.getId(), publicTool);
    }

    /**
     * This tests that you can convert a published public tool to private if it has a tool maintainer email set
     */
    @Test
    public void testPublicToPrivateToPublicTool() {
        ApiClient client = getWebClient(USER_1_USERNAME, testingPostgres);
        ContainersApi toolsApi = new ContainersApi(client);

        // Manual publish public repo
        DockstoreTool tool = manualRegisterAndPublish(toolsApi, "dockstoretestuser", "private_test_repo", "tool1",
            "git@github.com:DockstoreTestUser/dockstore-whalesay.git", "/Dockstore.cwl", "/Dockstore.wdl", "/Dockerfile",
            DockstoreTool.RegistryEnum.DOCKER_HUB, "master", "latest", true);

        // NOTE: The tool should not have an associated email

        // Give the tool a tool maintainer email and make private
        tool.setPrivateAccess(true);
        tool.setToolMaintainerEmail("testemail@domain.com");
        tool = toolsApi.updateContainer(tool.getId(), tool);

        // The tool should be private, published and have the correct email
        final long count = testingPostgres.runSelectStatement(
            "select count(*) from tool where ispublished='true' and privateaccess='true' and toolmaintaineremail='testemail@domain.com'",
            long.class);
        Assert.assertEquals("one tool should be private and published, there are " + count, 1, count);

        // Convert the tool back to public
        tool.setPrivateAccess(false);
        tool = toolsApi.updateContainer(tool.getId(), tool);

        // Check that the tool is no longer private
        final long count2 = testingPostgres.runSelectStatement(
            "select count(*) from tool where ispublished='true' and privateaccess='true' and toolmaintaineremail='testemail@domain.com'",
            long.class);
        Assert.assertEquals("no tool should be private, but there are " + count2, 0, count2);

    }

    /**
     * This tests that you can change a tool from public to private without a tool maintainer email, as long as an email is found in the descriptor
     */
    @Test
    public void testDefaultToEmailInDescriptorForPrivateRepos() {
        ApiClient client = getWebClient(USER_1_USERNAME, testingPostgres);
        ContainersApi toolsApi = new ContainersApi(client);

        // Manual publish public repo
        DockstoreTool tool = manualRegisterAndPublish(toolsApi, "dockstoretestuser", "private_test_repo", "tool1",
            "git@github.com:DockstoreTestUser/dockstore-whalesay-2.git", "/Dockstore.cwl", "/Dockstore.wdl", "/Dockerfile",
            DockstoreTool.RegistryEnum.DOCKER_HUB, "master", "latest", true);

        // NOTE: The tool should have an associated email

        // Make the tool private
        tool.setPrivateAccess(true);
        tool = toolsApi.updateContainer(tool.getId(), tool);
        tool = toolsApi.refresh(tool.getId());

        // The tool should be private, published and not have a maintainer email
        final long count = testingPostgres
            .runSelectStatement("select count(*) from tool where ispublished='true' and privateaccess='true' and toolmaintaineremail=''",
                long.class);
        Assert.assertEquals("one tool should be private and published, there are " + count, 1, count);

        // Convert the tool back to public
        tool.setPrivateAccess(false);
        tool = toolsApi.updateContainer(tool.getId(), tool);
        tool = toolsApi.refresh(tool.getId());

        // Check that the tool is no longer private
        final long count2 = testingPostgres.runSelectStatement(
            "select count(*) from tool where ispublished='true' and privateaccess='true' and toolmaintaineremail='testemail@domain.com'",
            long.class);
        Assert.assertEquals("no tool should be private, but there are " + count2, 0, count2);

        // Make the tool private but this time define a tool maintainer
        tool.setPrivateAccess(true);
        tool.setToolMaintainerEmail("testemail2@domain.com");
        tool = toolsApi.updateContainer(tool.getId(), tool);
        tool = toolsApi.refresh(tool.getId());

        // Check that the tool is no longer private
        final long count3 = testingPostgres.runSelectStatement(
            "select count(*) from tool where ispublished='true' and privateaccess='true' and toolmaintaineremail='testemail2@domain.com'",
            long.class);
        Assert.assertEquals("one tool should be private and published, there are " + count3, 1, count3);
    }

    /**
     * This tests that you cannot manually publish a private tool unless it has a tool maintainer email
     */
    @Test
    public void testPrivateManualPublishNoToolMaintainerEmail() {
        ApiClient client = getWebClient(USER_1_USERNAME, testingPostgres);
        ContainersApi toolsApi = new ContainersApi(client);

        // Manual publish private repo without tool maintainer email
        try {
            DockstoreTool tool = manualRegisterAndPublish(toolsApi, "dockstoretestuser", "private_test_repo", "",
                "git@github.com:DockstoreTestUser/dockstore-whalesay.git", "/Dockstore.cwl", "/Dockstore.wdl", "/Dockerfile",
                DockstoreTool.RegistryEnum.DOCKER_HUB, "master", "latest", true, true, null, null);
            fail("Should not be able to manually register due to missing email");
        } catch (ApiException e) {
            assertTrue(e.getMessage().contains("Tool maintainer email is required for private tools"));
        }

    }

    /**
     * This tests that you can manually publish a gitlab registry image
     */
    @Test
    @Category(SlowTest.class)
    public void testManualPublishGitlabDocker() {
        ApiClient client = getWebClient(USER_1_USERNAME, testingPostgres);
        ContainersApi toolsApi = new ContainersApi(client);

        // Manual publish
        DockstoreTool tool = manualRegisterAndPublish(toolsApi, "notarealnamespace", "notarealname", "alternate",
            "git@github.com:DockstoreTestUser/dockstore-whalesay.git", "/Dockstore.cwl", "/Dockstore.wdl", "/Dockerfile",
            DockstoreTool.RegistryEnum.DOCKER_HUB, "master", "latest", true, true, "duncan.andrew.g@gmail.com", null);

        // Check that tool exists and is published
        final long count = testingPostgres
            .runSelectStatement("select count(*) from tool where ispublished='true' and privateaccess='true'", long.class);
        Assert.assertEquals("one tool should be private and published, there are " + count, 1, count);

    }

    /**
     * This tests that you can't manually publish:
     * - A public Amazon ECR image (has a "public.ecr.aws" path) as a private tool
     * - A private Amazon ECR image (has a "*.dkr.ecr.*.amazonaws.com" path) as a public tool
     */
    @Test
    public void testManualPublishPrivateAccessAmazonECR() {
        ApiClient client = getWebClient(USER_1_USERNAME, testingPostgres);
        ContainersApi toolsApi = new ContainersApi(client);

        try {
            // Try to manual publish a public Amazon ECR tool using a private Amazon ECR image
            manualRegisterAndPublish(toolsApi, "notarealnamespace", "notarealname", "alternate",
                    "git@github.com:DockstoreTestUser/dockstore-whalesay.git", "/Dockstore.cwl", "/Dockstore.wdl", "/Dockerfile",
                    DockstoreTool.RegistryEnum.AMAZON_ECR, "master", "latest", true, false, null, "test.dkr.ecr.us-east-1.amazonaws.com");
            fail("Should not be able to register a public tool using a private Amazon ECR image.");
        } catch (ApiException e) {
            assertEquals("The private Amazon ECR tool cannot be set to public.", e.getMessage());
        }

        try {
            // Try to manual publish a private Amazon ECR tool using a public Amazon ECR image
            manualRegisterAndPublish(toolsApi, "notarealnamespace", "notarealname", "alternate",
                    "git@github.com:DockstoreTestUser/dockstore-whalesay.git", "/Dockstore.cwl", "/Dockstore.wdl", "/Dockerfile",
                    DockstoreTool.RegistryEnum.AMAZON_ECR, "master", "latest", true, true, "test@gmail.com", "public.ecr.aws/ubuntu/ubuntu");
            fail("Should not be able to register a private tool using a public Amazon ECR image.");
        } catch (ApiException e) {
            assertEquals("The public Amazon ECR tool cannot be set to private.", e.getMessage());
        }
    }

    /**
     * This tests that you can manually publish:
     * - A public Amazon ECR tool, but you can't change the tool to private.
     * - A private Amazon ECR tool, but you can't change the tool to public.
     *
     * Public and private Amazon ECR repositories have different docker paths, and an Amazon ECR repository cannot change its visibility once it's created.
     */
    @Test
    public void testManualPublishPrivateAccessUpdateAmazonECR() {
        ApiClient client = getWebClient(USER_1_USERNAME, testingPostgres);
        ContainersApi toolsApi = new ContainersApi(client);

        // Manual publish a private Amazon ECR tool
        DockstoreTool privateTool = manualRegisterAndPublish(toolsApi, "notarealnamespace", "notarealname", "alternate",
                "git@github.com:DockstoreTestUser/dockstore-whalesay.git", "/Dockstore.cwl", "/Dockstore.wdl", "/Dockerfile",
                DockstoreTool.RegistryEnum.AMAZON_ECR, "master", "latest", true, true, "duncan.andrew.g@gmail.com",
                "test.dkr.ecr.us-east-1.amazonaws.com");

        // Check that the tool is published and has correct values
        final long privateCount = testingPostgres.runSelectStatement(
                "select count(*) from tool where ispublished='true' and privateaccess='true' and registry='test.dkr.ecr.us-east-1.amazonaws.com' and namespace = 'notarealnamespace' and name = 'notarealname'",
                long.class);
        assertEquals("There should be one published, private Amazon ECR tool. There are " + privateCount, 1, privateCount);

        // Update tool to public (shouldn't work)
        privateTool.setPrivateAccess(false);
        try {
            toolsApi.updateContainer(privateTool.getId(), privateTool);
            fail("Should not be able to update private Amazon ECR tool to public");
        } catch (ApiException e) {
            assertTrue(e.getMessage().contains("The private Amazon ECR tool cannot be set to public."));
        }

        // Manual publish a public Amazon ECR tool
        DockstoreTool publicTool = manualRegisterAndPublish(toolsApi, "notarealnamespace", "notarealname", "alternate",
                "git@github.com:DockstoreTestUser/dockstore-whalesay.git", "/Dockstore.cwl", "/Dockstore.wdl", "/Dockerfile",
                DockstoreTool.RegistryEnum.AMAZON_ECR, "master", "latest", true);

        // Check that tool is published and has correct values
        final long publicCount = testingPostgres.runSelectStatement(
                "select count(*) from tool where ispublished='true' and privateaccess='false'", long.class);
        assertEquals("There should be one published, public Amazon ECR tool. There are " + publicCount, 1, publicCount);

        // Update tool to private (shouldn't work)
        publicTool.setPrivateAccess(true);
        publicTool.setToolMaintainerEmail("testemail@domain.com");
        try {
            toolsApi.updateContainer(publicTool.getId(), publicTool);
            fail("Should not be able to update public Amazon ECR tool to private");
        } catch (ApiException e) {
            assertEquals("The public Amazon ECR tool cannot be set to private.", e.getMessage());
        }
    }

    /**
     * This tests that you can't create public Amazon ECR tools with the same tool paths.
     * Amazon ECR allows slashes in the repository names, so this can lead to duplicate tool paths where two tools may have different names and tool names,
     * but end up with the same tool paths.
     */
    @Test
    public void testManualPublishDuplicatePublicAmazonECR() {
        ApiClient client = getWebClient(USER_1_USERNAME, testingPostgres);
        ContainersApi toolsApi = new ContainersApi(client);

        // Scenario 1:
        // First tool has tool path '<registry>/<namespace>/<repo-name-part-1>/<repo-name-part-2>' -> public.ecr.aws/abcd1234/foo/bar
        // Second tool has tool path '<registry>/<namespace>/<repo-name>/<tool-name>' -> public.ecr.aws/abcd1234/foo/bar
        DockstoreTool publicTool = manualRegisterAndPublish(toolsApi, "abcd1234", "foo/bar", null,
                "git@github.com:DockstoreTestUser/dockstore-whalesay.git", "/Dockstore.cwl", "/Dockstore.wdl", "/Dockerfile",
                DockstoreTool.RegistryEnum.AMAZON_ECR, "master", "latest", true);

        try {
            manualRegisterAndPublish(toolsApi, "abcd1234", "foo", "bar", "git@github.com:DockstoreTestUser/dockstore-whalesay.git",
                    "/Dockstore.cwl", "/Dockstore.wdl", "/Dockerfile", DockstoreTool.RegistryEnum.AMAZON_ECR, "master", "latest", true);
            fail("Should not have been able to register a public Amazon ECR tool with the same tool path.");
        } catch (ApiException e) {
            assertEquals("Tool " + publicTool.getToolPath() + " already exists.", e.getMessage());
        }

        // Scenario 2:
        // First tool has tool path '<registry>/<namespace>/<repo-name>/<tool-name>' -> public.ecr.aws/wxyz6789/potato/tomato
        // Second tool has tool path '<registry>/<namespace>/<repo-name-part-1>/<repo-name-part-2>' -> public.ecr.aws/wxyz6789/potato/tomato
        publicTool = manualRegisterAndPublish(toolsApi, "abcd1234", "potato", "tomato",
                "git@github.com:DockstoreTestUser/dockstore-whalesay.git", "/Dockstore.cwl", "/Dockstore.wdl", "/Dockerfile",
                DockstoreTool.RegistryEnum.AMAZON_ECR, "master", "latest", true);

        try {
            // Manual publish a public Amazon ECR tool using an image of the following format: public.ecr.aws/abcd1234/foo/bar and no tool name
            manualRegisterAndPublish(toolsApi, "abcd1234", "potato/tomato", null, "git@github.com:DockstoreTestUser/dockstore-whalesay.git",
                    "/Dockstore.cwl", "/Dockstore.wdl", "/Dockerfile", DockstoreTool.RegistryEnum.AMAZON_ECR, "master", "latest", true);
            fail("Should not have been able to register a public Amazon ECR tool with the same tool path.");
        } catch (ApiException e) {
            assertEquals("Tool " + publicTool.getToolPath() + " already exists.", e.getMessage());
        }
    }

    /**
     * This tests that you can't create private Amazon ECR tools with the same tool paths.
     * Amazon ECR allows slashes in the repository names, so this can lead to duplicate tool paths where two tools may have different names and tool names,
     * but end up with the same tool paths.
     */
    @Test
    public void testManualPublishDuplicatePrivateAmazonECR() {
        ApiClient client = getWebClient(USER_1_USERNAME, testingPostgres);
        ContainersApi toolsApi = new ContainersApi(client);

        // Scenario 1:
        // First tool has tool path '<registry>/<namespace>/<repo-name-part-1>/<repo-name-part-2>' -> public.ecr.aws/abcd1234/foo/bar
        // Second tool has tool path '<registry>/<namespace>/<repo-name>/<tool-name>' -> public.ecr.aws/abcd1234/foo/bar
        DockstoreTool privateTool = manualRegisterAndPublish(toolsApi, "abcd1234", "foo/bar", null,
                "git@github.com:DockstoreTestUser/dockstore-whalesay.git", "/Dockstore.cwl", "/Dockstore.wdl", "/Dockerfile",
                DockstoreTool.RegistryEnum.AMAZON_ECR, "master", "latest", true, true, "test@gmail.com", "test.dkr.ecr.us-east-1.amazonaws.com");

        try {
            manualRegisterAndPublish(toolsApi, "abcd1234", "foo", "bar", "git@github.com:DockstoreTestUser/dockstore-whalesay.git",
                    "/Dockstore.cwl", "/Dockstore.wdl", "/Dockerfile", DockstoreTool.RegistryEnum.AMAZON_ECR, "master", "latest", true,
                    true, "test@gmail.com", "test.dkr.ecr.us-east-1.amazonaws.com");
            fail("Should not have been able to register a private Amazon ECR tool with the same tool path.");
        } catch (ApiException e) {
            assertEquals("Tool " + privateTool.getToolPath() + " already exists.", e.getMessage());
        }

        // Scenario 2:
        // First tool has tool path '<registry>/<namespace>/<repo-name>/<tool-name>' -> public.ecr.aws/wxyz6789/potato/tomato
        // Second tool has tool path '<registry>/<namespace>/<repo-name-part-1>/<repo-name-part-2>' -> public.ecr.aws/wxyz6789/potato/tomato
        privateTool = manualRegisterAndPublish(toolsApi, "abcd1234", "potato", "tomato",
                "git@github.com:DockstoreTestUser/dockstore-whalesay.git", "/Dockstore.cwl", "/Dockstore.wdl", "/Dockerfile",
                DockstoreTool.RegistryEnum.AMAZON_ECR, "master", "latest", true, true, "test@gmail.com", "test.dkr.ecr.us-east-1.amazonaws.com");

        try {
            // Manual publish a public Amazon ECR tool using an image of the following format: public.ecr.aws/abcd1234/foo/bar and no tool name
            manualRegisterAndPublish(toolsApi, "abcd1234", "potato/tomato", null, "git@github.com:DockstoreTestUser/dockstore-whalesay.git",
                    "/Dockstore.cwl", "/Dockstore.wdl", "/Dockerfile", DockstoreTool.RegistryEnum.AMAZON_ECR, "master", "latest", true,
                    true, "test@gmail.com", "test.dkr.ecr.us-east-1.amazonaws.com");
            fail("Should not have been able to register a private Amazon ECR tool with the same tool path.");
        } catch (ApiException e) {
            assertEquals("Tool " + privateTool.getToolPath() + " already exists.", e.getMessage());
        }
    }

    /**
     * This tests that you can get an Amazon ECR tool by tool path and you can get a list of Amazon ECR tools by path (published and unpublished).
     * Specifically testing this because Amazon ECR supports slashes in its repository names.
     */
    @Test
    public void testGetContainerByPathsAmazonECR() {
        ApiClient client = getWebClient(USER_1_USERNAME, testingPostgres);
        ContainersApi toolsApi = new ContainersApi(client);
        DockstoreTool tool;
        DockstoreTool foundTool;

        // Manual publish a public Amazon ECR tool that has a repo name without slashes
        tool = manualRegisterAndPublish(toolsApi, "abcd1234", "foo", null,
                "git@github.com:DockstoreTestUser/dockstore-whalesay.git", "/Dockstore.cwl", "/Dockstore.wdl", "/Dockerfile",
                DockstoreTool.RegistryEnum.AMAZON_ECR, "master", "latest", true);

        try {
            foundTool = toolsApi.getContainerByToolPath(tool.getToolPath(), "");
            assertEquals(tool.getId(), foundTool.getId());
        } catch (ApiException e) {
            fail("Should have been able to get the Amazon ECR tool by tool path.");
        }

        try {
            foundTool = toolsApi.getPublishedContainerByToolPath(tool.getToolPath(), "");
            assertEquals(tool.getId(), foundTool.getId());
        } catch (ApiException e) {
            fail("Should have been able to get the published Amazon ECR tool by tool path.");
        }

        // Manual publish a public Amazon ECR tool that has a repo name with slashes
        tool = manualRegisterAndPublish(toolsApi, "abcd1234", "foo/bar", null,
                "git@github.com:DockstoreTestUser/dockstore-whalesay.git", "/Dockstore.cwl", "/Dockstore.wdl", "/Dockerfile",
                DockstoreTool.RegistryEnum.AMAZON_ECR, "master", "latest", true);

        try {
            foundTool = toolsApi.getContainerByToolPath(tool.getToolPath(), "");
            assertEquals(tool.getId(), foundTool.getId());
        } catch (ApiException e) {
            fail("Should have been able to get the Amazon ECR tool by tool path.");
        }

        try {
            foundTool = toolsApi.getPublishedContainerByToolPath(tool.getToolPath(), "");
            assertEquals(tool.getId(), foundTool.getId());
        } catch (ApiException e) {
            fail("Should have been able to get the published Amazon ECR tool by tool path.");
        }

        // Manual publish a public Amazon ECR tool that has a repo name with slashes and a tool name
        tool = manualRegisterAndPublish(toolsApi, "abcd1234", "foo/bar", "test-tool-name",
                "git@github.com:DockstoreTestUser/dockstore-whalesay.git", "/Dockstore.cwl", "/Dockstore.wdl", "/Dockerfile",
                DockstoreTool.RegistryEnum.AMAZON_ECR, "master", "latest", true);

        try {
            foundTool = toolsApi.getContainerByToolPath(tool.getToolPath(), "");
            assertEquals(tool.getId(), foundTool.getId());
        } catch (ApiException e) {
            fail("Should have been able to get the Amazon ECR tool by tool path.");
        }

        try {
            foundTool = toolsApi.getPublishedContainerByToolPath(tool.getToolPath(), "");
            assertEquals(tool.getId(), foundTool.getId());
        } catch (ApiException e) {
            fail("Should have been able to get the published Amazon ECR tool by tool path.");
        }

        try {
            List<DockstoreTool> foundTools = toolsApi.getContainerByPath(tool.getPath());
            assertEquals("Should have two tools with the path 'public.ecr.aws/abcd1234/foo/bar'.", 2, foundTools.size());
        } catch (ApiException e) {
            fail("Should have been able to get the Amazon ECR tools by path.");
        }

        try {
            List<DockstoreTool> foundTools = toolsApi.getPublishedContainerByPath(tool.getPath());
            assertEquals("Should have two published tools with the path 'public.ecr.aws/abcd1234/foo/bar'.", 2, foundTools.size());
        } catch (ApiException e) {
            fail("Should have been able to get the published Amazon ECR tools by path.");
        }
    }

    /**
     * This tests that entry paths are split into their registry, org, repo, and entry name components correctly.
     */
    @Test
    public void testSplitPath() {
        final int registryIndex = 0;
        final int orgIndex = 1;
        final int repoIndex = 2;
        final int entryNameIndex = 3;

        // Test splitting paths with no entry names
        String[] pathComponents = Entry.splitPath("registry/org/repo", false);
        assertEquals("registry", pathComponents[registryIndex]);
        assertEquals("org", pathComponents[orgIndex]);
        assertEquals("repo", pathComponents[repoIndex]);
        assertNull(pathComponents[entryNameIndex]);

        pathComponents = Entry.splitPath("registry/org/repo-part-1/repo-part-2", false);
        assertEquals("registry", pathComponents[registryIndex]);
        assertEquals("org", pathComponents[orgIndex]);
        assertEquals("repo-part-1/repo-part-2", pathComponents[repoIndex]);
        assertNull(pathComponents[entryNameIndex]);

        // Test a 3 part repo name. Amazon ECR registry allows repository names with more than 1 slash
        pathComponents = Entry.splitPath("registry/org/repo-part-1/repo-part-2/repo-part-3", false);
        assertEquals("registry", pathComponents[registryIndex]);
        assertEquals("org", pathComponents[orgIndex]);
        assertEquals("repo-part-1/repo-part-2/repo-part-3", pathComponents[repoIndex]);
        assertNull(pathComponents[entryNameIndex]);

        // Test splitting paths with entry name
        // Shouldn't really be setting hasEntryName to true if it doesn't have one, but it shouldn't affect the result for a path that contains a repo name with NO slashes
        pathComponents = Entry.splitPath("registry/org/repo", true);
        assertEquals("registry", pathComponents[registryIndex]);
        assertEquals("org", pathComponents[orgIndex]);
        assertEquals("repo", pathComponents[repoIndex]);
        assertNull(pathComponents[entryNameIndex]);

        pathComponents = Entry.splitPath("registry/org/repo/entry-name", true);
        assertEquals("registry", pathComponents[registryIndex]);
        assertEquals("org", pathComponents[orgIndex]);
        assertEquals("repo", pathComponents[repoIndex]);
        assertEquals("entry-name", pathComponents[entryNameIndex]);

        pathComponents = Entry.splitPath("registry/org/repo-part-1/repo-part-2/entry-name", true);
        assertEquals("registry", pathComponents[registryIndex]);
        assertEquals("org", pathComponents[orgIndex]);
        assertEquals("repo-part-1/repo-part-2", pathComponents[repoIndex]);
        assertEquals("entry-name", pathComponents[entryNameIndex]);

        pathComponents = Entry.splitPath("registry/org/repo-part-1/repo-part-2/repo-part-3/entry-name", true);
        assertEquals("registry", pathComponents[registryIndex]);
        assertEquals("org", pathComponents[orgIndex]);
        assertEquals("repo-part-1/repo-part-2/repo-part-3", pathComponents[repoIndex]);
        assertEquals("entry-name", pathComponents[entryNameIndex]);
    }

    /**
     * This tests that you can manually publish a private only registry (Seven Bridges), but you can't change the tool to public
     */
    @Test
    public void testManualPublishSevenBridgesTool() {
        ApiClient client = getWebClient(USER_1_USERNAME, testingPostgres);
        ContainersApi toolsApi = new ContainersApi(client);

        // Manual publish
        DockstoreTool tool = manualRegisterAndPublish(toolsApi, "notarealnamespace", "notarealname", "alternate",
            "git@github.com:DockstoreTestUser/dockstore-whalesay.git", "/Dockstore.cwl", "/Dockstore.wdl", "/Dockerfile",
            DockstoreTool.RegistryEnum.SEVEN_BRIDGES, "master", "latest", true, true, "duncan.andrew.g@gmail.com", "images.sbgenomics.com");

        // Check that tool is published and has correct values
        final long count = testingPostgres.runSelectStatement(
            "select count(*) from tool where ispublished='true' and privateaccess='true' and registry='images.sbgenomics.com' and namespace = 'notarealnamespace' and name = 'notarealname'",
            long.class);
        assertEquals("one tool should be private, published and from seven bridges, there are " + count, 1, count);

        // Update tool to public (shouldn't work)
        tool.setPrivateAccess(false);
        try {
            toolsApi.updateContainer(tool.getId(), tool);
            fail("Should not be able to update tool to public");
        } catch (ApiException e) {
            assertTrue(e.getMessage().contains("The registry Seven Bridges is private only, cannot set tool to public"));
        }
    }

    /**
     * This tests that you can't manually publish a private only registry (Seven Bridges) with an incorrect registry path
     */
    @Test
    public void testManualPublishSevenBridgesToolIncorrectRegistryPath() {
        ApiClient client = getWebClient(USER_1_USERNAME, testingPostgres);
        ContainersApi toolsApi = new ContainersApi(client);

        // Manual publish correct path
        DockstoreTool tool = manualRegisterAndPublish(toolsApi, "notarealnamespace", "notarealname", "alternate",
            "git@github.com:DockstoreTestUser/dockstore-whalesay.git", "/Dockstore.cwl", "/Dockstore.wdl", "/Dockerfile",
            DockstoreTool.RegistryEnum.SEVEN_BRIDGES, "master", "latest", true, true, "duncan.andrew.g@gmail.com",
            "test-images.sbgenomics.com");

        // Check that tool is published and has correct values
        final long count = testingPostgres.runSelectStatement(
            "select count(*) from tool where ispublished='true' and privateaccess='true' and registry='test-images.sbgenomics.com' and namespace = 'notarealnamespace' and name = 'notarealname'",
            long.class);
        assertEquals("one tool should be private, published and from seven bridges, there are " + count, 1, count);

        // Manual publish incorrect path
        try {
            manualRegisterAndPublish(toolsApi, "notarealnamespace", "notarealname", "alternate",
                "git@github.com:DockstoreTestUser/dockstore-whalesay.git", "/Dockstore.cwl", "/Dockstore.wdl", "/Dockerfile",
                DockstoreTool.RegistryEnum.SEVEN_BRIDGES, "master", "latest", true, true, "duncan.andrew.g@gmail.com",
                "testimages.sbgenomics.com");
            fail("Should not be able to register");
        } catch (ApiException e) {
            assertTrue(e.getMessage().contains("The provided registry is not valid"));
        }
    }

    /**
     * This tests that you can't manually publish a private only registry as public
     */
    @Test
    public void testManualPublishPrivateOnlyRegistryAsPublic() {
        // Manual publish
        ApiClient client = getWebClient(USER_1_USERNAME, testingPostgres);
        ContainersApi toolsApi = new ContainersApi(client);

        try {
            DockstoreTool tool = manualRegisterAndPublish(toolsApi, "notarealnamespace", "notarealname", "alternate",
                    "git@github.com:DockstoreTestUser/dockstore-whalesay.git", "/Dockstore.cwl", "/Dockstore.wdl", "/Dockerfile",
                    DockstoreTool.RegistryEnum.SEVEN_BRIDGES, "master", "latest", true, false, "duncan.andrew.g@gmail.com",
                    "images.sbgenomics.com");
            fail("Should fail since it is a private only registry with a public tool");
        } catch (ApiException e) {
            assertTrue(e.getMessage().contains("The registry Seven Bridges is a private only registry"));
        }
    }

    /**
     * This tests that you can't manually publish a tool from a registry that requires a custom docker path without specifying the path
     */
    @Test
    public void testManualPublishCustomDockerPathRegistry() {
        // Manual publish
        ApiClient client = getWebClient(USER_1_USERNAME, testingPostgres);
        ContainersApi toolsApi = new ContainersApi(client);

        try {
            DockstoreTool tool = manualRegisterAndPublish(toolsApi, "notarealnamespace", "notarealname", "alternate",
                "git@github.com:DockstoreTestUser/dockstore-whalesay.git", "/Dockstore.cwl", "/Dockstore.wdl", "/Dockerfile",
                DockstoreTool.RegistryEnum.SEVEN_BRIDGES, "master", "latest", true, true, "duncan.andrew.g@gmail.com", null);
            fail("Should fail due to no custom docker path");
        } catch (ApiException e) {
            assertTrue(e.getMessage().contains("The provided registry is not valid"));
        }
    }


    @Test
    public void testGettingSourceFilesForTag() {
        final ApiClient webClient = getWebClient(USER_1_USERNAME, testingPostgres);
        final io.dockstore.openapi.client.ApiClient openAPIWebClient = getOpenAPIWebClient(USER_1_USERNAME, testingPostgres);
        ContainersApi toolApi = new ContainersApi(webClient);
        io.dockstore.openapi.client.api.ContainertagsApi toolTagsApi = new io.dockstore.openapi.client.api.ContainertagsApi(openAPIWebClient);

        // Sourcefiles for tags
        DockstoreTool tool = toolApi.getContainerByToolPath("quay.io/dockstoretestuser/quayandgithub", null);
        Tag tag = tool.getWorkflowVersions().stream().filter(existingTag -> Objects.equals(existingTag.getName(), "master")).findFirst().get();

        List<SourceFile> sourceFiles = toolTagsApi.getTagsSourcefiles(tool.getId(), tag.getId(), null);
        Assert.assertNotNull(sourceFiles);
        Assert.assertEquals(3, sourceFiles.size());

        // Check that filtering works
        List<String> fileTypes = new ArrayList<>();
        fileTypes.add(DescriptorLanguage.FileType.DOCKERFILE.toString());
        fileTypes.add(DescriptorLanguage.FileType.DOCKSTORE_CWL.toString());
        fileTypes.add(DescriptorLanguage.FileType.DOCKSTORE_WDL.toString());

        sourceFiles = toolTagsApi.getTagsSourcefiles(tool.getId(), tag.getId(), fileTypes);
        Assert.assertNotNull(sourceFiles);
        Assert.assertEquals(3, sourceFiles.size());

        fileTypes.remove(1);
        sourceFiles = toolTagsApi.getTagsSourcefiles(tool.getId(), tag.getId(), fileTypes);
        Assert.assertNotNull(sourceFiles);
        Assert.assertEquals(2, sourceFiles.size());

        fileTypes.clear();
        fileTypes.add(DescriptorLanguage.FileType.NEXTFLOW_CONFIG.toString());
        sourceFiles = toolTagsApi.getTagsSourcefiles(tool.getId(), tag.getId(), fileTypes);
        Assert.assertNotNull(sourceFiles);
        Assert.assertEquals(0, sourceFiles.size());

        // Check that you can't grab a tag's sourcefiles if it doesn't belong to the tool.
        DockstoreTool tool2 = manualRegisterAndPublish(toolApi, "dockstoretestuser", "private_test_repo", "tool1",
                "git@github.com:DockstoreTestUser/dockstore-whalesay-2.git", "/Dockstore.cwl", "/Dockstore.wdl", "/Dockerfile",
                DockstoreTool.RegistryEnum.DOCKER_HUB, "master", "latest", true);
        Tag tool2tag = tool2.getWorkflowVersions().get(0);

        try {
            sourceFiles = toolTagsApi.getTagsSourcefiles(tool.getId(), tool2tag.getId(), null);
            Assert.fail("Shouldn't be able to get a tag's sourcefiles if it doesn't belong to the tool.");
        } catch (io.dockstore.openapi.client.ApiException ex) {
            Assert.assertEquals("Version " + tool2tag.getId() + " does not exist for this entry", ex.getMessage());
        }


        // check that sourcefiles can't be viewed by another user if they aren't published
        final io.dockstore.openapi.client.ApiClient user2OpenAPIWebClient = getOpenAPIWebClient(USER_2_USERNAME, testingPostgres);
        io.dockstore.openapi.client.api.ContainertagsApi user2toolTagsApi = new io.dockstore.openapi.client.api.ContainertagsApi(user2OpenAPIWebClient);
        try {
            sourceFiles = user2toolTagsApi.getTagsSourcefiles(tool.getId(), tag.getId(), null);
            Assert.fail("Should not be able to grab sourcefiles if not published and doesn't belong to user.");
        } catch (io.dockstore.openapi.client.ApiException ex) {
            Assert.assertEquals("Forbidden: you do not have the credentials required to access this entry.", ex.getMessage());
        }

        // sourcefiles can be viewed by others once published
        tool = toolApi.publish(tool.getId(), CommonTestUtilities.createPublishRequest(true));
        sourceFiles = user2toolTagsApi.getTagsSourcefiles(tool.getId(), tag.getId(), null);
        Assert.assertNotNull(sourceFiles);
        Assert.assertEquals(3, sourceFiles.size());
    }

}
