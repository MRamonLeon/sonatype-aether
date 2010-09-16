package org.sonatype.aether.test.util.connector.suite;

/*
 * Copyright (c) 2010 Sonatype, Inc. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0, 
 * and you may not use this file except in compliance with the Apache License Version 2.0. 
 * You may obtain a copy of the Apache License Version 2.0 at http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing, 
 * software distributed under the Apache License Version 2.0 is distributed on an 
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. 
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */

import static org.junit.Assert.*;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;
import org.sonatype.aether.metadata.Metadata;
import org.sonatype.aether.repository.RepositoryPolicy;
import org.sonatype.aether.spi.connector.ArtifactDownload;
import org.sonatype.aether.spi.connector.ArtifactUpload;
import org.sonatype.aether.spi.connector.MetadataDownload;
import org.sonatype.aether.spi.connector.MetadataUpload;
import org.sonatype.aether.spi.connector.RepositoryConnector;
import org.sonatype.aether.spi.connector.Transfer;
import org.sonatype.aether.spi.connector.Transfer.State;
import org.sonatype.aether.test.util.TestFileUtils;
import org.sonatype.aether.test.util.impl.StubArtifact;
import org.sonatype.aether.test.util.impl.StubMetadata;
import org.sonatype.aether.transfer.NoRepositoryConnectorException;

/**
 * The ConnectorTestSuite bundles standard tests for {@link RepositoryConnector}s.
 * <p>
 * To use these tests, provide a (Junit4-)class extending this class, and provide a default constructor calling
 * {@link ConnectorTestSuite#ConnectorTestSuite(ConnectorTestSetup)} with a self-implemented {@link ConnectorTestSetup}.
 * 
 * @author Benjamin Hanzelmann
 */
public abstract class ConnectorTestSuite
    extends ConnectorTestSuiteSetup
{

    public ConnectorTestSuite( ConnectorTestSetup setup )
    {
        super( setup );
    }

    /**
     * Test successful event order.
     * 
     * @see TransferEventTester#testSuccessfulTransferEvents(RepositoryConnectorFactory, TestRepositorySystemSession,
     *      RemoteRepository)
     */
    @Test
    public void testSuccessfulEvents()
        throws NoRepositoryConnectorException, IOException
    {
        TransferEventTester.testSuccessfulTransferEvents( factory(), session, repository );
    }

    @Test
    public void testFailedEvents()
        throws NoRepositoryConnectorException, IOException
    {
        TransferEventTester.testFailedTransferEvents( factory(), session, repository );
    }

    @Test
    public void testFileHandleLeakage()
        throws IOException, NoRepositoryConnectorException
    {

        StubArtifact artifact = new StubArtifact( "testGroup", "testArtifact", "jar", "", "1-test" );
        StubMetadata metadata =
            new StubMetadata( "testGroup", "testArtifact", "1-test", "maven-metadata.xml",
                              Metadata.Nature.RELEASE_OR_SNAPSHOT );

        RepositoryConnector connector = factory().newInstance( session, repository );

        File tmpFile = TestFileUtils.createTempFile( "testFileHandleLeakage" );
        ArtifactUpload artUp = new ArtifactUpload( artifact, tmpFile );
        connector.put( Arrays.asList( artUp ), null );
        assertTrue( "Leaking file handle in artifact upload", tmpFile.delete() );

        tmpFile = TestFileUtils.createTempFile( "testFileHandleLeakage" );
        MetadataUpload metaUp = new MetadataUpload( metadata, tmpFile );
        connector.put( null, Arrays.asList( metaUp ) );
        assertTrue( "Leaking file handle in metadata upload", tmpFile.delete() );

        tmpFile = TestFileUtils.createTempFile( "testFileHandleLeakage" );
        ArtifactDownload artDown = new ArtifactDownload( artifact, null, tmpFile, null );
        connector.get( Arrays.asList( artDown ), null );
        new File( tmpFile.getAbsolutePath() + ".sha1" ).deleteOnExit();
        assertTrue( "Leaking file handle in artifact download", tmpFile.delete() );

        tmpFile = TestFileUtils.createTempFile( "testFileHandleLeakage" );
        MetadataDownload metaDown = new MetadataDownload( metadata, null, tmpFile, null );
        connector.get( null, Arrays.asList( metaDown ) );
        new File( tmpFile.getAbsolutePath() + ".sha1" ).deleteOnExit();
        assertTrue( "Leaking file handle in metadata download", tmpFile.delete() );
    }

    @Test
    public void testBlocking()
        throws NoRepositoryConnectorException, IOException
    {

        RepositoryConnector connector = factory().newInstance( session, repository );

        int count = 10;

        byte[] pattern = "tmpFile".getBytes( "UTF-8" );
        File tmpFile = TestFileUtils.createTempFile( pattern, 100000 );

        List<ArtifactUpload> artUps = ConnectorTestUtils.createTransfers( ArtifactUpload.class, count, tmpFile );
        List<MetadataUpload> metaUps = ConnectorTestUtils.createTransfers( MetadataUpload.class, count, tmpFile );
        List<ArtifactDownload> artDowns = ConnectorTestUtils.createTransfers( ArtifactDownload.class, count, null );
        List<MetadataDownload> metaDowns = ConnectorTestUtils.createTransfers( MetadataDownload.class, count, null );

        // this should block until all transfers are done - racing condition, better way to test this?
        connector.put( artUps, metaUps );
        connector.get( artDowns, metaDowns );

        for ( int i = 0; i < count; i++ )
        {
            ArtifactUpload artUp = artUps.get( i );
            MetadataUpload metaUp = metaUps.get( i );
            ArtifactDownload artDown = artDowns.get( i );
            MetadataDownload metaDown = metaDowns.get( i );

            assertTrue( Transfer.State.DONE.equals( artUp.getState() ) );
            assertTrue( Transfer.State.DONE.equals( artDown.getState() ) );
            assertTrue( Transfer.State.DONE.equals( metaUp.getState() ) );
            assertTrue( Transfer.State.DONE.equals( metaDown.getState() ) );
        }

    }

    @Test
    public void testMkdirConcurrencyBug()
        throws IOException, NoRepositoryConnectorException
    {
        RepositoryConnector connector = factory().newInstance( session, repository );
        File tmpFile = TestFileUtils.createTempFile( "mkdirsBug" );

        int numTransfers = 2;

        ArtifactUpload[] artUps = new ArtifactUpload[numTransfers];
        MetadataUpload[] metaUps = new MetadataUpload[numTransfers];


        for ( int i = 0; i < numTransfers; i++ )
        {
            StubArtifact art = new StubArtifact( "testGroup", "testArtifact", "jar", "", i + "-test" );
            StubMetadata meta =
                new StubMetadata( "testGroup", "testArtifact", i + "-test", "maven-metadata.xml",
                                  Metadata.Nature.RELEASE_OR_SNAPSHOT );

            ArtifactUpload artUp = new ArtifactUpload( art, tmpFile );
            MetadataUpload metaUp = new MetadataUpload( meta, tmpFile );

            artUps[i] = artUp;
            metaUps[i] = metaUp;
        }

        connector.put( Arrays.asList( artUps ), null );
        connector.put( null, Arrays.asList( metaUps ) );

        File localRepo = new File( "target/con-test" );

        StringBuilder localPath = new StringBuilder( localRepo.getAbsolutePath() );

        for ( int i = 0; i < 50; i++ )
        {
            localPath.append( "/d" );
        }

        ArtifactDownload[] artDowns = new ArtifactDownload[numTransfers];
        MetadataDownload[] metaDowns = new MetadataDownload[numTransfers];

        for ( int m = 0; m < 30; m++ )
        {
            for ( int i = 0; i < numTransfers; i++ )
            {
                File artFile = new File( localPath.toString() + "/a" + i );
                File metaFile = new File( localPath.toString() + "/m" + i );

                StubArtifact art = new StubArtifact( "testGroup", "testArtifact", "jar", "", i + "-test" );
                StubMetadata meta =
                    new StubMetadata( "testGroup", "testArtifact", i + "-test", "maven-metadata.xml",
                                      Metadata.Nature.RELEASE_OR_SNAPSHOT );

                ArtifactDownload artDown =
                    new ArtifactDownload( art, null, artFile, RepositoryPolicy.CHECKSUM_POLICY_FAIL );
                MetadataDownload metaDown =
                    new MetadataDownload( meta, null, metaFile, RepositoryPolicy.CHECKSUM_POLICY_FAIL );

                artDowns[i] = artDown;
                metaDowns[i] = metaDown;
            }

            connector.get( Arrays.asList( artDowns ), Arrays.asList( metaDowns ) );

            for ( int j = 0; j < numTransfers; j++ )
            {
                ArtifactDownload artDown = artDowns[j];
                MetadataDownload metaDown = metaDowns[j];

                assertNull( "artifact download had exception: " + artDown.getException(), artDown.getException() );
                assertNull( "metadata download had exception: " + metaDown.getException(), metaDown.getException() );
                assertEquals( State.DONE, artDown.getState() );
                assertEquals( State.DONE, metaDown.getState() );
            }

            TestFileUtils.deleteDir( localRepo );
        }

    }

}
