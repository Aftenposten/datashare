package org.icij.datashare.text.indexing.elasticsearch;

import org.apache.tika.metadata.DublinCore;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.ParsingReader;
import org.elasticsearch.action.get.GetRequest;
import org.elasticsearch.action.get.GetResponse;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.index.reindex.DeleteByQueryRequest;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.icij.datashare.HumanReadableSize;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.com.Channel;
import org.icij.datashare.com.Message;
import org.icij.datashare.com.Message.Field;
import org.icij.datashare.com.Publisher;
import org.icij.datashare.test.ElasticsearchRule;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.Hasher;
import org.icij.datashare.text.Language;
import org.icij.datashare.text.Project;
import org.icij.extract.document.DocumentFactory;
import org.icij.extract.document.PathIdentifier;
import org.icij.extract.document.TikaDocument;
import org.icij.extract.extractor.Extractor;
import org.icij.spewer.FieldNames;
import org.icij.task.Options;
import org.junit.After;
import org.junit.ClassRule;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import static java.nio.file.Paths.get;
import static org.apache.tika.metadata.HttpHeaders.CONTENT_TYPE;
import static org.elasticsearch.action.support.WriteRequest.RefreshPolicy.IMMEDIATE;
import static org.fest.assertions.Assertions.assertThat;
import static org.fest.assertions.MapAssert.entry;
import static org.icij.datashare.test.ElasticsearchRule.TEST_INDEX;
import static org.icij.datashare.text.DocumentBuilder.createDoc;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

public class ElasticsearchSpewerTest {
    @ClassRule
    public static ElasticsearchRule es = new ElasticsearchRule();
    private final Publisher publisher = Mockito.mock(Publisher.class);

    private final ElasticsearchSpewer spewer = new ElasticsearchSpewer(es.client,
            text -> Language.ENGLISH, new FieldNames(), publisher, new PropertiesProvider()).withRefresh(IMMEDIATE).withIndex("test-datashare");

    @Test
    public void test_simple_write() throws Exception {
        final TikaDocument document = new DocumentFactory().withIdentifier(new PathIdentifier()).create(get("test-file.txt"));
        final ParsingReader reader = new ParsingReader(new ByteArrayInputStream("test".getBytes()));
        document.setReader(reader);

        spewer.write(document);

        GetResponse documentFields = es.client.get(new GetRequest(TEST_INDEX, document.getId()), RequestOptions.DEFAULT);
        assertThat(documentFields.isExists()).isTrue();
        assertThat(documentFields.getId()).isEqualTo(document.getId());
        assertEquals(new HashMap<String, String>() {{
            put("name", "Document");
        }}, documentFields.getSourceAsMap().get("join"));

        ArgumentCaptor<Message> argument = ArgumentCaptor.forClass(Message.class);
        verify(publisher).publish(eq(Channel.NLP), argument.capture());
        assertThat(argument.getValue().content).includes(entry(Field.DOC_ID, document.getId()));
    }

    @Test
    public void test_write_with_correct_iso1_language() throws Exception {
        Path path = get(Objects.requireNonNull(getClass().getResource("/docs/a/b/c/zho.txt")).getPath());
        DocumentFactory documentFactory = new DocumentFactory().configure(Options.from(new HashMap<>() {{
            put("language", "zho");
        }}));
        TikaDocument document = new Extractor(documentFactory).extract(path);

        spewer.write(document);

        GetResponse documentFields = es.client.get(new GetRequest(TEST_INDEX, document.getId()), RequestOptions.DEFAULT);
        assertThat(documentFields.getSourceAsMap()).includes(
                entry("language", "CHINESE")
        );
    }

    @Test
    public void test_write_with_correct_iso2_language() throws Exception {
        Path path = get(Objects.requireNonNull(getClass().getResource("/docs/a/b/c/jpn.txt")).getPath());
        DocumentFactory documentFactory = new DocumentFactory().configure(Options.from(new HashMap<>() {{
            put("language", "jpn");
        }}));
        TikaDocument document = new Extractor(documentFactory).extract(path);

        spewer.write(document);

        GetResponse documentFields = es.client.get(new GetRequest(TEST_INDEX, document.getId()), RequestOptions.DEFAULT);
        assertThat(documentFields.getSourceAsMap()).includes(
                entry("language", "JAPANESE")
        );
    }

    @Test
    public void test_write_with_correct_language_name() throws Exception {
        Path path = get(Objects.requireNonNull(getClass().getResource("/docs/a/b/c/maltese.txt")).getPath());
        DocumentFactory documentFactory = new DocumentFactory().configure(Options.from(new HashMap<>() {{
            put("language", "Maltese");
        }}));
        TikaDocument document = new Extractor(documentFactory).extract(path);

        spewer.write(document);

        GetResponse documentFields = es.client.get(new GetRequest(TEST_INDEX, document.getId()), RequestOptions.DEFAULT);
        assertThat(documentFields.getSourceAsMap()).includes(
                entry("language", "MALTESE")
        );
    }

    @Test(expected = IllegalArgumentException.class)
    public void test_write_with_incorrect_language() throws Exception {
        Path path = get(Objects.requireNonNull(getClass().getResource("/docs/a/b/c/doc.txt")).getPath());

        DocumentFactory documentFactory = new DocumentFactory().configure(Options.from(new HashMap<>() {{
            put("language", "foo");
        }}));
        TikaDocument document = new Extractor(documentFactory).extract(path);
        spewer.write(document);
    }

    @Test
    public void test_write_without_language() throws Exception {
        Path path = get(Objects.requireNonNull(getClass().getResource("/docs/a/b/c/doc.txt")).getPath());
        TikaDocument document = new Extractor().extract(path);

        spewer.write(document);

        GetResponse documentFields = es.client.get(new GetRequest(TEST_INDEX, document.getId()), RequestOptions.DEFAULT);
        assertThat(documentFields.getSourceAsMap()).includes(
                entry("language", "ENGLISH")
        );
    }


    @Test
    public void test_metadata() throws Exception {
        Path path = get(Objects.requireNonNull(getClass().getResource("/docs/a/b/c/doc.txt")).getPath());
        TikaDocument document = new Extractor().extract(path);

        spewer.write(document);

        GetResponse documentFields = es.client.get(new GetRequest(TEST_INDEX, document.getId()), RequestOptions.DEFAULT);
        assertThat(documentFields.getSourceAsMap()).includes(
                entry("contentEncoding", "ISO-8859-1"),
                entry("contentType", "text/plain"),
                entry("nerTags", new ArrayList<>()),
                entry("contentLength", 45),
                entry("contentTextLength", 45),
                entry("status", "INDEXED"),
                entry("path", path.toString()),
                entry("dirname", path.getParent().toString())
        );
    }

    @Test
    public void test_long_content_length() throws Exception {
        final TikaDocument document = new DocumentFactory().withIdentifier(new PathIdentifier()).create(get("t-file.txt"));
        final ParsingReader reader = new ParsingReader(new ByteArrayInputStream("test".getBytes()));
        document.setReader(reader);
        document.getMetadata().set("Content-Length", "7862117376");

        spewer.write(document);

        GetResponse documentFields = es.client.get(new GetRequest(TEST_INDEX, document.getId()), RequestOptions.DEFAULT);
        assertThat(documentFields.getSourceAsMap()).includes(entry("contentLength", 7862117376L));
    }

    @Test
    public void test_title_and_title_norm() throws Exception {
        final TikaDocument document = new DocumentFactory().withIdentifier(new PathIdentifier()).create(get("T-File.txt"));
        final ParsingReader reader = new ParsingReader(new ByteArrayInputStream("test".getBytes()));
        document.setReader(reader);

        spewer.write(document);

        GetResponse documentFields = es.client.get(new GetRequest(TEST_INDEX, document.getId()), RequestOptions.DEFAULT);
        assertThat(documentFields.getSourceAsMap()).includes(entry("title", "T-File.txt"));
        assertThat(documentFields.getSourceAsMap()).includes(entry("titleNorm", "t-file.txt"));
    }

    @Test
    public void test_title_and_title_norm_for_an_tweet() throws Exception {
        final TikaDocument document = new DocumentFactory().withIdentifier(new PathIdentifier()).create(get("Tweet-File.json"));
        final ParsingReader reader = new ParsingReader(new ByteArrayInputStream("test".getBytes()));
        document.setReader(reader);
        document.getMetadata().set(CONTENT_TYPE, "application/json; twint");

        spewer.write(document);

        GetResponse documentFields = es.client.get(new GetRequest(TEST_INDEX, document.getId()), RequestOptions.DEFAULT);
        assertThat(documentFields.getSourceAsMap()).includes(entry("title", "Tweet-File.json"));
        assertThat(documentFields.getSourceAsMap()).includes(entry("titleNorm", "tweet-file.json"));
    }

    @Test
    public void test_title_and_title_norm_for_an_tweet_with_dc_title() throws Exception {
        final TikaDocument document = new DocumentFactory().withIdentifier(new PathIdentifier()).create(get("Tweet-File.json"));
        final ParsingReader reader = new ParsingReader(new ByteArrayInputStream("test".getBytes()));
        document.setReader(reader);
        document.getMetadata().set(CONTENT_TYPE, "application/json; twint");
        document.getMetadata().set(DublinCore.TITLE, "This is a tweet.");

        spewer.write(document);

        GetResponse documentFields = es.client.get(new GetRequest(TEST_INDEX, document.getId()), RequestOptions.DEFAULT);
        assertThat(documentFields.getSourceAsMap()).includes(entry("title", "This is a tweet."));
        assertThat(documentFields.getSourceAsMap()).includes(entry("titleNorm", "this is a tweet."));
    }

    @Test
    public void test_title_and_title_norm_for_an_email() throws Exception {
        final TikaDocument document = new DocumentFactory().withIdentifier(new PathIdentifier()).create(get("Email-File.txt"));
        final ParsingReader reader = new ParsingReader(new ByteArrayInputStream("test".getBytes()));
        document.setReader(reader);
        document.getMetadata().set(CONTENT_TYPE, "message/http");

        spewer.write(document);

        GetResponse documentFields = es.client.get(new GetRequest(TEST_INDEX, document.getId()), RequestOptions.DEFAULT);
        assertThat(documentFields.getSourceAsMap()).includes(entry("title", "Email-File.txt"));
        assertThat(documentFields.getSourceAsMap()).includes(entry("titleNorm", "email-file.txt"));
    }

    @Test
    public void test_title_and_title_norm_for_an_email_with_dc_title() throws Exception {
        final TikaDocument document = new DocumentFactory().withIdentifier(new PathIdentifier()).create(get("Email-File.txt"));
        final ParsingReader reader = new ParsingReader(new ByteArrayInputStream("test".getBytes()));
        document.setReader(reader);
        document.getMetadata().set(CONTENT_TYPE, "message/http");
        document.getMetadata().set(DublinCore.TITLE, "This is an email.");

        spewer.write(document);

        GetResponse documentFields = es.client.get(new GetRequest(TEST_INDEX, document.getId()), RequestOptions.DEFAULT);
        assertThat(documentFields.getSourceAsMap()).includes(entry("title", "This is an email."));
        assertThat(documentFields.getSourceAsMap()).includes(entry("titleNorm", "this is an email."));
    }

    @Test
    public void test_title_and_title_norm_for_an_email_with_dc_subject() throws Exception {
        final TikaDocument document = new DocumentFactory().withIdentifier(new PathIdentifier()).create(get("Email-File.txt"));
        final ParsingReader reader = new ParsingReader(new ByteArrayInputStream("test".getBytes()));
        document.setReader(reader);
        document.getMetadata().set(CONTENT_TYPE, "message/http");
        document.getMetadata().set(DublinCore.TITLE, "This is an email.");
        document.getMetadata().set(DublinCore.SUBJECT, "This is a more detailed email.");

        spewer.write(document);

        GetResponse documentFields = es.client.get(new GetRequest(TEST_INDEX, document.getId()), RequestOptions.DEFAULT);
        assertThat(documentFields.getSourceAsMap()).includes(entry("title", "This is a more detailed email."));
        assertThat(documentFields.getSourceAsMap()).includes(entry("titleNorm", "this is a more detailed email."));
    }

    @Test
    public void test_tika_metadata_unknown_tag_is_blocked() throws IOException {
        final TikaDocument document = new DocumentFactory().withIdentifier(new PathIdentifier()).create(get("doc.txt"));
        final ParsingReader reader = new ParsingReader(new ByteArrayInputStream("test".getBytes()));
        document.setReader(reader);
        document.getMetadata().set("foo", "bar");
        document.getMetadata().set("unknown_tag_0x", "unknown");
        spewer.write(document);

        GetResponse documentFields = es.client.get(new GetRequest(TEST_INDEX, document.getId()), RequestOptions.DEFAULT);
        assertThat(documentFields.getSourceAsMap().containsKey("metadata")).isTrue();
        @SuppressWarnings("unchecked")
        HashMap<String, Object> metadata = (HashMap<String, Object>) documentFields.getSourceAsMap().get("metadata");
        // Those values should be here
        assertThat(metadata).includes(entry("tika_metadata_resourcename", "doc.txt"));
        assertThat(metadata).includes(entry("tika_metadata_foo", "bar"));
        // This value should be blocked
        assertThat(metadata).excludes(entry("tika_metadata_unknown_tag_0x", "unknown"));
    }

    @Test
    public void test_embedded_document() throws Exception {
        Path path = get(Objects.requireNonNull(getClass().getResource("/docs/embedded_doc.eml")).getPath());
        final TikaDocument document = new Extractor().extract(path);

        spewer.write(document);

        GetResponse documentFields = es.client.get(new GetRequest(TEST_INDEX, document.getId()), RequestOptions.DEFAULT);
        assertTrue(documentFields.isExists());

        SearchRequest searchRequest = new SearchRequest();
        SearchSourceBuilder searchSourceBuilder = new SearchSourceBuilder();
        searchSourceBuilder.query(QueryBuilders.multiMatchQuery("simple.tiff", "content"));
        searchRequest.source(searchSourceBuilder);
        SearchResponse response = es.client.search(searchRequest, RequestOptions.DEFAULT);
        assertThat(response.getHits().getTotalHits().value).isGreaterThan(0);
        //assertThat(response.getHits().getAt(0).getId()).endsWith("embedded.pdf");

        verify(publisher, times(2)).publish(eq(Channel.NLP), any(Message.class));
    }

    @Test
    public void test_extract_id_should_be_equal_to_datashare_id() throws IOException {
        Options<String> digestAlgorithm = Options.from(new HashMap<>() {{
            put("digestAlgorithm", Document.DEFAULT_DIGESTER.toString());
            put("digestProjectName", "project");
        }});
        DocumentFactory tikaFactory = new DocumentFactory().configure(digestAlgorithm);
        Extractor extractor = new Extractor(tikaFactory).configure(digestAlgorithm);

        final TikaDocument extractDocument = extractor.extract(get(Objects.requireNonNull(getClass().getResource("/docs/embedded_doc.eml")).getPath()));
        Document document = createDoc(Project.project("project"),get(Objects.requireNonNull(getClass().getResource("/docs/embedded_doc.eml")).getPath()))
                .with("This is a document to be parsed by datashare.")
                .with(Language.FRENCH)
                .ofMimeType("text/plain")
                .with(convert(extractDocument.getMetadata()))
                .with(Document.Status.INDEXED)
                .withContentLength(45L)
                .build();

        assertThat(document.getId()).isEqualTo(extractDocument.getId());
    }

    @Test
    public void test_duplicate_file() throws Exception {
        HashMap<String, String> digestProperties = new HashMap<>() {{
            put("digestAlgorithm", "SHA-256");
            put("digestProjectName", "project");
        }};
        ElasticsearchSpewer spewer256 = new ElasticsearchSpewer(es.client,
                text -> Language.ENGLISH, new FieldNames(), publisher, new PropertiesProvider(digestProperties)).withRefresh(IMMEDIATE).withIndex("test-datashare");
        Options<String> from = Options.from(digestProperties);
        DocumentFactory tikaFactory = new DocumentFactory().configure(from);
        Extractor extractor = new Extractor(tikaFactory).configure(from);

        final TikaDocument document = extractor.extract(get(Objects.requireNonNull(getClass().getResource("/docs/doc.txt")).getPath()));
        final TikaDocument document2 = extractor.extract(get(Objects.requireNonNull(getClass().getResource("/docs/doc-duplicate.txt")).getPath()));

        spewer256.write(document);
        spewer256.write(document2);

        GetResponse actualDocument = es.client.get(new GetRequest(TEST_INDEX, document.getId()),RequestOptions.DEFAULT);
        GetResponse actualDocument2 = es.client.get(new GetRequest(TEST_INDEX, Hasher.SHA_256.hash(document2.getPath())), RequestOptions.DEFAULT);
        assertThat(actualDocument.isExists()).isTrue();
        assertThat(actualDocument.getSourceAsMap()).includes(entry("type", "Document"));
        assertThat(actualDocument2.isExists()).isTrue();
        assertThat(actualDocument2.getSourceAsMap()).includes(entry("type", "Duplicate"));
        assertThat(actualDocument2.getId().length()).isEqualTo(Hasher.SHA_256.digestLength);
    }

    @Test
    public void test_truncated_content() throws Exception {
        ElasticsearchSpewer limitedContentSpewer = new ElasticsearchSpewer(es.client,
                text -> Language.ENGLISH, new FieldNames(), publisher, new PropertiesProvider(new HashMap<>() {{
            put("maxContentLength", "20");
        }})).withRefresh(IMMEDIATE).withIndex("test-datashare");
        final TikaDocument document = new DocumentFactory().withIdentifier(new PathIdentifier()).create(get("fake-file.txt"));
        final ParsingReader reader = new ParsingReader(new ByteArrayInputStream("this content should be truncated".getBytes()));
        document.setReader(reader);

        limitedContentSpewer.write(document);

        GetResponse documentFields = es.client.get(new GetRequest(TEST_INDEX, document.getId()), RequestOptions.DEFAULT);
        assertThat(documentFields.getSourceAsMap()).includes(entry("content", "this content should"));
    }

    @Test
    public void test_truncated_content_if_document_is_smaller_than_limit() throws Exception {
        ElasticsearchSpewer limitedContentSpewer = new ElasticsearchSpewer(es.client,
                text -> Language.ENGLISH, new FieldNames(), publisher, new PropertiesProvider(new HashMap<>() {{
            put("maxContentLength", "20");
        }})).withRefresh(IMMEDIATE).withIndex("test-datashare");
        final TikaDocument document = new DocumentFactory().withIdentifier(new PathIdentifier()).create(get("ok-file.txt"));
        final ParsingReader reader = new ParsingReader(new ByteArrayInputStream("this content is ok".getBytes()));
        document.setReader(reader);

        limitedContentSpewer.write(document);

        GetResponse documentFields = es.client.get(new GetRequest(TEST_INDEX, document.getId()), RequestOptions.DEFAULT);
        assertThat(documentFields.getSourceAsMap()).includes(entry("content", "this content is ok"));
    }

    @Test
    public void test_get_max_content_length_is_limited_to_2G() {
        assertThat(spewer.getMaxContentLength(new PropertiesProvider(new HashMap<>() {{put("maxContentLength", "20");}})))
                .isEqualTo(20);
        assertThat((long)spewer.getMaxContentLength(new PropertiesProvider(new HashMap<>() {{put("maxContentLength", "2G");}})))
                .isEqualTo(HumanReadableSize.parse("2G")-1); // Integer.MAX_VALUE
    }

    @After
    public void after() {
        try {
            DeleteByQueryRequest deleteByQueryRequest = new DeleteByQueryRequest("test-datashare");
            deleteByQueryRequest.setQuery(QueryBuilders.matchAllQuery());
            es.client.deleteByQuery(deleteByQueryRequest, RequestOptions.DEFAULT).getDeleted();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private Map<String, Object> convert(Metadata metadata) {
        Map<String, Object> map = new HashMap<>();
        for (String name: metadata.names()) {
            map.put(name, metadata.get(name));
        }
        return map;
    }
}
