package org.sonatype.aether.connector.async;

/*******************************************************************************
 * Copyright (c) 2010 Sonatype, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************/

import com.ning.http.client.AsyncHandler;
import com.ning.http.client.AsyncHttpClient;
import com.ning.http.client.AsyncHttpClientConfig;
import com.ning.http.client.FluentCaseInsensitiveStringsMap;
import com.ning.http.client.HttpResponseBodyPart;
import com.ning.http.client.HttpResponseHeaders;
import com.ning.http.client.ProxyServer;
import com.ning.http.client.ProxyServer.Protocol;
import com.ning.http.client.Realm;
import com.ning.http.client.Request;
import com.ning.http.client.RequestBuilder;
import com.ning.http.client.Response;
import com.ning.http.client.providers.netty.NettyAsyncHttpProvider;
import org.sonatype.aether.ConfigurationProperties;
import org.sonatype.aether.RepositorySystemSession;
import org.sonatype.aether.repository.Authentication;
import org.sonatype.aether.repository.Proxy;
import org.sonatype.aether.repository.RemoteRepository;
import org.sonatype.aether.repository.RepositoryPolicy;
import org.sonatype.aether.spi.connector.ArtifactDownload;
import org.sonatype.aether.spi.connector.ArtifactTransfer;
import org.sonatype.aether.spi.connector.ArtifactUpload;
import org.sonatype.aether.spi.connector.MetadataDownload;
import org.sonatype.aether.spi.connector.MetadataTransfer;
import org.sonatype.aether.spi.connector.MetadataUpload;
import org.sonatype.aether.spi.connector.RepositoryConnector;
import org.sonatype.aether.spi.connector.Transfer;
import org.sonatype.aether.spi.io.FileProcessor;
import org.sonatype.aether.spi.log.Logger;
import org.sonatype.aether.transfer.ArtifactNotFoundException;
import org.sonatype.aether.transfer.ArtifactTransferException;
import org.sonatype.aether.transfer.ChecksumFailureException;
import org.sonatype.aether.transfer.MetadataNotFoundException;
import org.sonatype.aether.transfer.MetadataTransferException;
import org.sonatype.aether.transfer.NoRepositoryConnectorException;
import org.sonatype.aether.transfer.TransferEvent;
import org.sonatype.aether.transfer.TransferEvent.EventType;
import org.sonatype.aether.transfer.TransferEvent.RequestType;
import org.sonatype.aether.transfer.TransferListener;
import org.sonatype.aether.transfer.TransferResource;
import org.sonatype.aether.util.ChecksumUtils;
import org.sonatype.aether.util.StringUtils;
import org.sonatype.aether.util.layout.MavenDefaultLayout;
import org.sonatype.aether.util.layout.RepositoryLayout;
import org.sonatype.aether.util.listener.DefaultTransferEvent;
import org.sonatype.aether.util.listener.DefaultTransferResource;

import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A repository connector that uses the Async Http Client.
 *
 * @author Jeanfrancois Arcand
 */
class AsyncRepositoryConnector
    implements RepositoryConnector
{
    private final Logger logger;

    private final FileProcessor fileProcessor;

    private final RemoteRepository repository;

    private final AsyncHttpClient httpClient;

    private final Map<String, String> checksumAlgos;

    private final AtomicBoolean closed = new AtomicBoolean( false );

    private final RepositoryLayout layout = new MavenDefaultLayout();

    private final TransferListener listener;

    private final RepositorySystemSession session;

    private boolean useCache = false;

    private final boolean disableResumeSupport;

    private final ConcurrentHashMap<RandomAccessFile, Boolean> activeDownloadFiles = new ConcurrentHashMap<RandomAccessFile, Boolean>();

    /**
     * Create an {@link org.sonatype.aether.connector.async.AsyncRepositoryConnector} instance which connect to the
     * {@link RemoteRepository}
     *
     * @param repository the remote repository
     * @param session    the {@link RepositorySystemSession}
     * @param logger     the logger.
     * @throws NoRepositoryConnectorException
     */
    public AsyncRepositoryConnector( RemoteRepository repository, RepositorySystemSession session,
                                     FileProcessor fileProcessor, Logger logger )
        throws NoRepositoryConnectorException
    {
        this.logger = logger;
        this.repository = repository;
        this.listener = session.getTransferListener();
        this.fileProcessor = fileProcessor;
        this.session = session;

        if ( !"default".equals( repository.getContentType() ) )
        {
            throw new NoRepositoryConnectorException( repository );
        }

        if ( !repository.getProtocol().regionMatches( true, 0, "http", 0, "http".length() ) &&
            !repository.getProtocol().regionMatches( true, 0, "dav", 0, "dav".length() ) )
        {
            throw new NoRepositoryConnectorException( repository );
        }

        AsyncHttpClientConfig config = createConfig( session, repository , true);
        httpClient = new AsyncHttpClient( new NettyAsyncHttpProvider( config ) );

        checksumAlgos = new LinkedHashMap<String, String>();
        checksumAlgos.put( "SHA-1", ".sha1" );
        checksumAlgos.put( "MD5", ".md5" );

        disableResumeSupport = ConfigurationProperties.get( session, "aether.connector.ahc.disableResumable", false);
    }

    private Realm getRealm( RemoteRepository repository )
    {
        Realm realm = null;

        Authentication a = repository.getAuthentication();
        if ( a != null && a.getUsername() != null )
        {
            realm = new Realm.RealmBuilder().setPrincipal( a.getUsername() ).setPassword(
                a.getPassword() ).setUsePreemptiveAuth( false ).build();
        }

        return realm;
    }

    private ProxyServer getProxy( RemoteRepository repository )
    {
        ProxyServer proxyServer = null;

        Proxy p = repository.getProxy();
        if ( p != null )
        {
            Authentication a = p.getAuthentication();
            boolean useSSL = repository.getProtocol().equalsIgnoreCase( "https" ) ||
                repository.getProtocol().equalsIgnoreCase( "dav:https" );
            if ( a == null )
            {
                proxyServer = new ProxyServer( useSSL ? Protocol.HTTPS : Protocol.HTTP, p.getHost(), p.getPort() );
            }
            else
            {
                proxyServer =
                    new ProxyServer( useSSL ? Protocol.HTTPS : Protocol.HTTP, p.getHost(), p.getPort(), a.getUsername(),
                                     a.getPassword() );
            }
        }

        return proxyServer;
    }

    /**
     * Create an {@link AsyncHttpClientConfig} instance based on the values from {@link RepositorySystemSession}
     *
     * @param session {link RepositorySystemSession}
     * @return a configured instance of
     */
    private AsyncHttpClientConfig createConfig( RepositorySystemSession session, RemoteRepository repository, boolean useCompression )
    {
        AsyncHttpClientConfig.Builder configBuilder = new AsyncHttpClientConfig.Builder();

        String userAgent = ConfigurationProperties.get( session, ConfigurationProperties.USER_AGENT,
                                                        ConfigurationProperties.DEFAULT_USER_AGENT );
        if ( !StringUtils.isEmpty( userAgent ) )
        {
            configBuilder.setUserAgent( userAgent );
        }
        int connectTimeout = ConfigurationProperties.get( session, ConfigurationProperties.CONNECT_TIMEOUT,
                                                          ConfigurationProperties.DEFAULT_CONNECT_TIMEOUT );

        configBuilder.setConnectionTimeoutInMs( connectTimeout );
        configBuilder.setCompressionEnabled( useCompression );
        configBuilder.setFollowRedirects( true );
        configBuilder.setRequestTimeoutInMs(
            ConfigurationProperties.get( session, ConfigurationProperties.REQUEST_TIMEOUT,
                                         ConfigurationProperties.DEFAULT_REQUEST_TIMEOUT ) );

        configBuilder.setProxyServer( getProxy( repository ) );
        configBuilder.setRealm( getRealm( repository ) );

        return configBuilder.build();
    }

    /**
     * Use the async http client library to download artifacts and metadata.
     *
     * @param artifactDownloads The artifact downloads to perform, may be {@code null} or empty.
     * @param metadataDownloads The metadata downloads to perform, may be {@code null} or empty.
     */
    public void get( Collection<? extends ArtifactDownload> artifactDownloads,
                     Collection<? extends MetadataDownload> metadataDownloads )
    {
        if ( closed.get() )
        {
            throw new IllegalStateException( "connector closed" );
        }

        artifactDownloads = safe( artifactDownloads );
        metadataDownloads = safe( metadataDownloads );

        CountDownLatch latch = new CountDownLatch( artifactDownloads.size() + metadataDownloads.size() );

        Collection<GetTask<?>> tasks = new ArrayList<GetTask<?>>();

        for ( MetadataDownload download : metadataDownloads )
        {
            String resource = layout.getPath( download.getMetadata() ).getPath();
            GetTask<?> task =
                new GetTask<MetadataTransfer>( resource, download.getFile(), download.getChecksumPolicy(), latch,
                                               download, METADATA, false );
            tasks.add( task );
            task.run();
        }

        for ( ArtifactDownload download : artifactDownloads )
        {
            String resource = layout.getPath( download.getArtifact() ).getPath();
            GetTask<?> task =
                new GetTask<ArtifactTransfer>( resource, download.isExistenceCheck() ? null : download.getFile(),
                                               download.getChecksumPolicy(), latch, download, ARTIFACT, true );
            tasks.add( task );
            task.run();
        }

        try
        {
            latch.await();

            for ( GetTask<?> task : tasks )
            {
                task.flush();
            }
        }
        catch ( InterruptedException e )
        {
            for ( GetTask<?> task : tasks )
            {
                task.flush( e );
            }
        }

    }

    /**
     * Use the async http client library to upload artifacts and metadata.
     *
     * @param artifactUploads The artifact uploads to perform, may be {@code null} or empty.
     * @param metadataUploads The metadata uploads to perform, may be {@code null} or empty.
     */
    public void put( Collection<? extends ArtifactUpload> artifactUploads,
                     Collection<? extends MetadataUpload> metadataUploads )
    {
        if ( closed.get() )
        {
            throw new IllegalStateException( "connector closed" );
        }

        artifactUploads = safe( artifactUploads );
        metadataUploads = safe( metadataUploads );

        CountDownLatch latch = new CountDownLatch( artifactUploads.size() + metadataUploads.size() );

        Collection<PutTask<?>> tasks = new ArrayList<PutTask<?>>();

        for ( ArtifactUpload upload : artifactUploads )
        {
            String path = layout.getPath( upload.getArtifact() ).getPath();

            PutTask<?> task = new PutTask<ArtifactTransfer>( path, upload.getFile(), latch, upload, ARTIFACT );
            tasks.add( task );
            task.run();
        }

        for ( MetadataUpload upload : metadataUploads )
        {
            String path = layout.getPath( upload.getMetadata() ).getPath();

            PutTask<?> task = new PutTask<MetadataTransfer>( path, upload.getFile(), latch, upload, METADATA );
            tasks.add( task );
            task.run();
        }

        try
        {
            latch.await();

            for ( PutTask<?> task : tasks )
            {
                task.flush();
            }
        }
        catch ( InterruptedException e )
        {
            for ( PutTask<?> task : tasks )
            {
                task.flush( e );
            }
        }
    }

    private void handleResponseCode( String url, int responseCode, String responseMsg )
        throws AuthorizationException, ResourceDoesNotExistException, TransferException
    {
        if ( responseCode == HttpURLConnection.HTTP_NOT_FOUND )
        {
            throw new ResourceDoesNotExistException(
                String.format( "Unable to locate resource %s. Error code %s", url, responseCode ) );
        }

        if ( responseCode == HttpURLConnection.HTTP_FORBIDDEN || responseCode == HttpURLConnection.HTTP_UNAUTHORIZED ||
            responseCode == HttpURLConnection.HTTP_PROXY_AUTH )
        {
            throw new AuthorizationException(
                String.format( "Access denied to %s. Error code %s, %s", url, responseCode, responseMsg ) );
        }

        if ( responseCode >= HttpURLConnection.HTTP_MULT_CHOICE )
        {
            throw new TransferException(
                String.format( "Failed to transfer %s. Error code %s, %s", url, responseCode, responseMsg ) );
        }
    }

    private TransferEvent newEvent( TransferResource resource, Exception e, TransferEvent.RequestType requestType,
                                    TransferEvent.EventType eventType )
    {
        DefaultTransferEvent event = new DefaultTransferEvent();
        event.setResource( resource );
        event.setRequestType( requestType );
        event.setType( eventType );
        event.setException( e );
        return event;
    }

    class GetTask<T extends Transfer>
        implements Runnable
    {

        private final T download;

        private final String path;

        private final File file;

        private final String checksumPolicy;

        private final LatchGuard latch;

        private volatile Exception exception;

        private final ExceptionWrapper<T> wrapper;

        private final AtomicBoolean deleteFile = new AtomicBoolean(true);

        private final boolean allowResumable;

        public GetTask( String path, File file, String checksumPolicy, CountDownLatch latch, T download,
                        ExceptionWrapper<T> wrapper, boolean allowResumable )
        {
            this.path = path;
            this.file = file;
            this.checksumPolicy = checksumPolicy;
            this.allowResumable = allowResumable;
            this.latch = new LatchGuard( latch );
            this.download = download;
            this.wrapper = wrapper;
        }

        public T getDownload()
        {
            return download;
        }

        public Exception getException()
        {
            return exception;
        }

        public void run()
        {
            download.setState( Transfer.State.ACTIVE );
            final String uri = validateUri( path );
            final TransferResource transferResource = new DefaultTransferResource( repository.getUrl(), path, file );
            final boolean ignoreChecksum = RepositoryPolicy.CHECKSUM_POLICY_IGNORE.equals( checksumPolicy );
            CompletionHandler completionHandler = null;

            final FileLockCompanion fileLockCompanion = ( file != null ) ? createOrGetTmpFile( file.getPath(), allowResumable ) : new FileLockCompanion( null, null );

            try
            {
                final ChecksumTransferListener sha1 = new ChecksumTransferListener( "SHA-1" );
                final ChecksumTransferListener md5 = new ChecksumTransferListener( "MD5" );

                long length = 0;
                if ( fileLockCompanion.getFile() != null )
                {
                    fileProcessor.mkdirs( fileLockCompanion.getFile().getParentFile() );
                }

                // Position the file to the end in case we are resuming an aborded download.
                final RandomAccessFile resumableFile = fileLockCompanion.getFile() == null ? null : new RandomAccessFile( fileLockCompanion.getFile(), "rws" );
                if (resumableFile != null) {
                    length = resumableFile.length();
                }

                FluentCaseInsensitiveStringsMap headers = new FluentCaseInsensitiveStringsMap();
                if ( !useCache )
                {
                    headers.add( "Pragma", "no-cache" );
                }
                headers.add( "Accept", "text/html, image/gif, image/jpeg, *; q=.2, */*; q=.2" );

                Request request = null;
                final AtomicInteger maxRequestTry = new AtomicInteger();
                AsyncHttpClient client = httpClient;
                final AtomicBoolean closeOnComplete = new AtomicBoolean(false);

                /**
                 * If length > 0, it means we are resuming a interrupted download. If that's the case,
                 * we can't re-use the current httpClient because compression is enabled, and supporting
                 * compression per request is not supported in ahc and may never has it could have
                 * a performance impact.
                 */
                if ( length > 0 )
                {
                    AsyncHttpClientConfig config = createConfig( session, repository, false );
                    client = new AsyncHttpClient( new NettyAsyncHttpProvider( config ) );
                    request = client.prepareGet( uri ).setRangeOffset( length ).setHeaders( headers ).build();
                    closeOnComplete.set(true);
                }
                else
                {
                    request = httpClient.prepareGet( uri ).setHeaders( headers ).build();
                }

                final Request activeRequest = request;
                final AsyncHttpClient activeHttpClient = client;
                completionHandler = new CompletionHandler( transferResource, httpClient, logger, RequestType.GET )
                {
                    private final AtomicBoolean acceptRange = new AtomicBoolean(false);

                    /**
                     * {@inheritDoc}
                     */
                    @Override
                    public STATE onHeadersReceived( final HttpResponseHeaders headers )
                        throws Exception
                    {

                        FluentCaseInsensitiveStringsMap h = headers.getHeaders();
                        String rangeByteValue = h.getFirstValue( "Content-Range" );
                        // Make sure the server acceptance of the range requests headers
                        if ( rangeByteValue != null && rangeByteValue.compareToIgnoreCase( "none" ) != 0 )
                        {
                            acceptRange.set( true );
                        }
                        return super.onHeadersReceived( headers );
                    }

                    @Override
                    public void onThrowable( Throwable t )
                    {
                        try
                        {
                            /**
                             * If an IOException occurs, let's try to resume the request based on how much bytes has
                             * been so far downloaded. Fail after IOException.
                             */
                            if ( maxRequestTry.get() < 3 && IOException.class.isAssignableFrom( t.getClass() ) )
                            {
                                maxRequestTry.incrementAndGet();
                                Request newRequest =
                                    new RequestBuilder( activeRequest ).setRangeOffset( resumableFile.length() ).build();
                                activeHttpClient.executeRequest( newRequest, this );
                                deleteFile.set(false);
                                return;
                            }

                            if ( closeOnComplete.get() )
                            {
                                activeHttpClient.close();
                            }

                            super.onThrowable( t );
                            if ( Exception.class.isAssignableFrom( t.getClass() ) )
                            {
                                exception = Exception.class.cast( t );
                            }
                            else
                            {
                                exception = new Exception( t );
                            }
                            fireTransferFailed();
                        }
                        catch ( Throwable ex )
                        {
                            logger.debug( "Unexpected exception", ex );
                        }
                        finally
                        {
                            if ( resumableFile != null )
                            {
                                try
                                {
                                    resumableFile.close();
                                }
                                catch ( IOException ex )
                                {
                                }

                            }
                            deleteFile( fileLockCompanion );

                            latch.countDown();
                            removeListeners();
                        }
                    }

                    private void removeListeners()
                    {
                        if ( !ignoreChecksum )
                        {
                            removeTransferListener( md5 );
                            removeTransferListener( sha1 );
                        }
                        removeTransferListener( listener );
                    }

                    public STATE onBodyPartReceived( final HttpResponseBodyPart content )
                        throws Exception
                    {
                        if ( status() != null && ( status().getStatusCode() == 200 || status().getStatusCode() == 206) )
                        {
                            byte[] bytes = content.getBodyPartBytes();
                            try
                            {
                                if ( acceptRange.get() ) {
                                    resumableFile.seek( fileLockCompanion.getFile().length() );
                                }
                                resumableFile.write( bytes );
                            }
                            catch ( IOException ex )
                            {
                                return AsyncHandler.STATE.ABORT;
                            }
                        }
                        return super.onBodyPartReceived( content );
                    }

                    @Override
                    public Response onCompleted( Response r )
                        throws Exception
                    {
                        try
                        {
                            final Response response = super.onCompleted( r );

                            try
                            {
                                resumableFile.close();
                            }
                            catch ( IOException ex )
                            {
                            }

                            handleResponseCode( uri, response.getStatusCode(), response.getStatusText() );

                            if ( !ignoreChecksum )
                            {
                                activeHttpClient.getConfig().executorService().execute( new Runnable()
                                {
                                    public void run()
                                    {
                                        try
                                        {
                                            try
                                            {
                                                if ( !verifyChecksum( file, uri, sha1.getActualChecksum(), ".sha1" ) &&
                                                    !verifyChecksum( file, uri, md5.getActualChecksum(), ".md5" ) )
                                                {
                                                    throw new ChecksumFailureException( "Checksum validation failed" +
                                                                                            ", no checksums available from the repository" );
                                                }
                                            }
                                            catch ( ChecksumFailureException e )
                                            {
                                                if ( RepositoryPolicy.CHECKSUM_POLICY_FAIL.equals( checksumPolicy ) )
                                                {
                                                    throw e;
                                                }
                                                if ( listener != null )
                                                {
                                                    listener.transferCorrupted(
                                                        newEvent( transferResource, e, RequestType.GET,
                                                                  EventType.CORRUPTED ) );
                                                }
                                            }
                                        }
                                        catch ( Exception ex )
                                        {
                                            exception = ex;
                                        }
                                        finally
                                        {
                                            if ( exception == null )
                                            {
                                                try
                                                {
                                                    rename( fileLockCompanion.getFile() , file );
                                                }
                                                catch ( IOException e )
                                                {
                                                    exception = e;
                                                }
                                            }

                                            deleteFile( fileLockCompanion );
                                            latch.countDown();

                                            if ( closeOnComplete.get() )
                                            {
                                                activeHttpClient.close();
                                            }
                                        }
                                    }
                                } );
                            }

                            if ( ignoreChecksum )
                            {
                                latch.countDown();
                            }

                            removeListeners();

                            return response;
                        }
                        catch ( Exception ex )
                        {
                            exception = ex;
                            throw ex;
                        }
                        finally
                        {
                            try
                            {
                                if ( fileLockCompanion.getFile() != null )
                                {
                                    if ( exception != null )
                                    {
                                        deleteFile( fileLockCompanion );
                                    }
                                    else if ( ignoreChecksum )
                                    {
                                        rename( fileLockCompanion.getFile(), file );
                                    }
                                }
                            }
                            catch ( IOException ex )
                            {
                                exception = ex;
                            }
                        }
                    }

                };


                try
                {
                    if ( file == null )
                    {
                        if ( !resourceExist( uri ) )
                        {
                            throw new ResourceDoesNotExistException(
                                "Could not find " + uri + " in " + repository.getUrl() );
                        }
                        latch.countDown();
                    }
                    else
                    {
                        if ( listener != null )
                        {
                            completionHandler.addTransferListener( listener );
                            listener.transferInitiated(
                                newEvent( transferResource, null, RequestType.GET, EventType.INITIATED ) );
                        }

                        if ( !ignoreChecksum )
                        {
                            completionHandler.addTransferListener( md5 );
                            completionHandler.addTransferListener( sha1 );
                        }

                        activeHttpClient.executeRequest( request, completionHandler );
                    }
                }
                catch ( Exception ex )
                {
                    deleteFile( fileLockCompanion );
                    exception = ex;
                    latch.countDown();
                }
            }
            catch ( Throwable t )
            {
                deleteFile( fileLockCompanion );
                try
                {
                    if ( Exception.class.isAssignableFrom( t.getClass() ) )
                    {
                        exception = Exception.class.cast( t );
                    }
                    else
                    {
                        exception = new Exception( t );
                    }
                    if ( listener != null )
                    {
                        listener.transferFailed(
                            newEvent( transferResource, exception, RequestType.GET, EventType.FAILED ) );
                    }
                }
                finally
                {
                    latch.countDown();
                }
            }
        }

        private void deleteFile( FileLockCompanion fileLockCompanion )
        {
            if ( fileLockCompanion != null && fileLockCompanion.getFile() == null && deleteFile.get() )
            {
                unlockFile ( fileLockCompanion );
            }
        }

        private boolean verifyChecksum( File file, String path, String actual, String ext )
            throws ChecksumFailureException
        {
            File tmp = getTmpFile( file.getPath() + ext );
            try
            {
                try
                {
                    Response response = httpClient.prepareGet( path + ext ).execute().get();

                    if ( response.getStatusCode() == HttpURLConnection.HTTP_NOT_FOUND )
                    {
                        return false;
                    }

                    OutputStream fs = new BufferedOutputStream( new FileOutputStream( tmp ) );
                    try
                    {
                        InputStream is = response.getResponseBodyAsStream();
                        final byte[] buffer = new byte[4 * 1024];
                        int n = 0;
                        while ( -1 != ( n = is.read( buffer ) ) )
                        {
                            fs.write( buffer, 0, n );
                        }
                        fs.flush();
                    }
                    finally
                    {
                        fs.close();
                    }

                }
                catch ( Exception ex )
                {
                    throw new ChecksumFailureException( ex );
                }

                String expected;

                try
                {
                    expected = ChecksumUtils.read( tmp );
                }
                catch ( IOException e )
                {
                    throw new ChecksumFailureException( e );
                }

                if ( expected.equalsIgnoreCase( actual ) )
                {
                    try
                    {
                        rename( tmp, new File( file.getPath() + ext ) );
                    }
                    catch ( IOException e )
                    {
                        // ignored, non-critical
                    }
                }
                else
                {
                    throw new ChecksumFailureException( expected, actual );
                }
            }
            finally
            {
                tmp.delete();
            }

            return true;
        }

        public void flush()
        {
            flush( null );
        }

        public void flush( Exception exception )
        {
            Exception e = this.exception;
            wrapper.wrap( download, ( e != null ) ? e : exception, repository );
            download.setState( Transfer.State.DONE );
        }

        private void rename( File from, File to )
            throws IOException
        {
            fileProcessor.move( from, to );
        }
    }

    class PutTask<T extends Transfer>
        implements Runnable
    {

        private final T upload;

        private final ExceptionWrapper<T> wrapper;

        private final String path;

        private final File file;

        private volatile Exception exception;

        private final LatchGuard latch;

        public PutTask( String path, File file, CountDownLatch latch, T upload, ExceptionWrapper<T> wrapper )
        {
            this.path = path;
            this.file = file;
            this.upload = upload;
            this.wrapper = wrapper;
            this.latch = new LatchGuard( latch );
        }

        public Exception getException()
        {
            return exception;
        }

        public void run()
        {
            upload.setState( Transfer.State.ACTIVE );
            final DefaultTransferResource transferResource =
                new DefaultTransferResource( repository.getUrl(), path, file );

            try
            {
                final String uri = validateUri( path );

                final CompletionHandler completionHandler =
                    new CompletionHandler( transferResource, httpClient, logger, RequestType.PUT )
                    {
                        @Override
                        public void onThrowable( Throwable t )
                        {
                            try
                            {
                                super.onThrowable( t );
                                if ( Exception.class.isAssignableFrom( t.getClass() ) )
                                {
                                    exception = Exception.class.cast( t );
                                }
                                else
                                {
                                    exception = new Exception( t );
                                }

                                if ( listener != null )
                                {
                                    listener.transferFailed(
                                        newEvent( transferResource, exception, RequestType.PUT, EventType.FAILED ) );
                                }
                            }
                            finally
                            {
                                latch.countDown();
                            }
                        }

                        @Override
                        public Response onCompleted( Response r )
                            throws Exception
                        {
                            try
                            {
                                Response response = super.onCompleted( r );
                                handleResponseCode( uri, response.getStatusCode(), response.getStatusText() );

                                httpClient.getConfig().executorService().execute( new Runnable()
                                {
                                    public void run()
                                    {
                                        try
                                        {
                                            uploadChecksums( file, uri );
                                        }
                                        catch ( Exception ex )
                                        {
                                            exception = ex;
                                        }
                                        finally
                                        {
                                            latch.countDown();
                                        }
                                    }
                                } );

                                return r;
                            }

                            catch ( Exception ex )
                            {
                                exception = ex;
                                throw ex;
                            }

                        }
                    };

                if ( listener != null )

                {
                    completionHandler.addTransferListener( listener );
                    listener.transferInitiated(
                        newEvent( transferResource, null, RequestType.PUT, EventType.INITIATED ) );
                }

                if ( file == null )
                {
                    throw new IllegalArgumentException( "no source file specified for upload" );
                }
                transferResource.setContentLength( file.length() );

                httpClient.preparePut( uri ).setBody(
                    new ProgressingFileBodyGenerator( file, completionHandler ) ).execute( completionHandler );
            }
            catch ( Exception e )
            {
                try
                {
                    if ( listener != null )
                    {
                        listener.transferFailed( newEvent( transferResource, e, RequestType.PUT, EventType.FAILED ) );
                    }
                    exception = e;
                }
                finally
                {
                    latch.countDown();
                }
            }
        }

        public void flush()
        {
            wrapper.wrap( upload, exception, repository );
            upload.setState( Transfer.State.DONE );
        }

        public void flush( Exception exception )
        {
            Exception e = this.exception;
            wrapper.wrap( upload, ( e != null ) ? e : exception, repository );
            upload.setState( Transfer.State.DONE );
        }

        private void uploadChecksums( File file, String path )
        {
            try
            {
                Map<String, Object> checksums = ChecksumUtils.calc( file, checksumAlgos.keySet() );
                for ( Map.Entry<String, Object> entry : checksums.entrySet() )
                {
                    uploadChecksum( file, path, entry.getKey(), entry.getValue() );
                }
            }
            catch ( IOException e )
            {
                logger.debug( "Failed to upload checksums for " + file + ": " + e.getMessage(), e );
            }
        }

        private void uploadChecksum( File file, String path, String algo, Object checksum )
        {
            try
            {
                if ( checksum instanceof Exception )
                {
                    throw (Exception) checksum;
                }

                String ext = checksumAlgos.get( algo );

                // Here we go blocking as this is a simple request.
                Response response =
                    httpClient.preparePut( path + ext ).setBody( String.valueOf( checksum ) ).execute().get();

                if ( response == null || response.getStatusCode() >= HttpURLConnection.HTTP_BAD_REQUEST )
                {
                    throw new TransferException(
                        String.format( "Checksum failed for %s with status code %s", path + ext, response == null
                            ? HttpURLConnection.HTTP_INTERNAL_ERROR
                            : response.getStatusCode() ) );
                }
            }
            catch ( Exception e )
            {
                logger.debug( "Failed to upload " + algo + " checksum for " + file + ": " + e.getMessage(), e );
            }
        }

    }

    /**
     * Builds a complete URL string from the repository URL and the relative path passed.
     *
     * @param path the relative path
     * @return the complete URL
     */
    private String buildUrl( String path )
    {
        final String repoUrl = repository.getUrl();
        path = path.replace( ' ', '+' );

        if ( repoUrl.charAt( repoUrl.length() - 1 ) != '/' )
        {
            return repoUrl + '/' + path;
        }
        return repoUrl + path;
    }

    private String validateUri( String path )
    {
        String tmpUri = buildUrl( path );
        // If we get dav request here, switch to http as no need for dav method.
        String dav = "dav";
        String davHttp = "dav:http";
        if ( tmpUri.startsWith( dav ) )
        {
            if ( tmpUri.startsWith( davHttp ) )
            {
                tmpUri = tmpUri.substring( dav.length() + 1 );
            }
            else
            {
                tmpUri = "http" + tmpUri.substring( dav.length() );
            }
        }
        return tmpUri;
    }

    private boolean resourceExist( String url )
        throws IOException, ExecutionException, InterruptedException, TransferException, AuthorizationException
    {
        int statusCode = httpClient.prepareHead( url ).execute().get().getStatusCode();

        switch ( statusCode )
        {
            case HttpURLConnection.HTTP_OK:
                return true;

            case HttpURLConnection.HTTP_FORBIDDEN:
                throw new AuthorizationException(
                    String.format( "Access denied to %s . Status code %s", url, statusCode ) );

            case HttpURLConnection.HTTP_NOT_FOUND:
                return false;

            case HttpURLConnection.HTTP_UNAUTHORIZED:
                throw new AuthorizationException(
                    String.format( "Access denied to %s . Status code %s", url, statusCode ) );

            default:
                throw new TransferException(
                    "Failed to look for file: " + buildUrl( url ) + ". Return code is: " + statusCode );
        }
    }

    static interface ExceptionWrapper<T>
    {
        void wrap( T transfer, Exception e, RemoteRepository repository );
    }

    public void close()
    {
        closed.set( true );
        httpClient.close();
    }

    private <T> Collection<T> safe( Collection<T> items )
    {
        return ( items != null ) ? items : Collections.<T>emptyList();
    }

    private FileLockCompanion createOrGetTmpFile( String path, boolean allowResumable)
    {
        if ( !disableResumeSupport && allowResumable )
        {
            File f = new File( path + ".ahc" );
            File parentFile = f.getParentFile();
            if ( parentFile.isDirectory() && !path.endsWith( ".ahc" ) )
            {
                for ( File tmpFile : parentFile.listFiles( new FilenameFilter()
                {
                    public boolean accept( File dir, String name )
                    {
                        if ( name.indexOf( "." ) > 0 && name.lastIndexOf( "." ) == name.indexOf( ".ahc" ) )
                        {
                            return true;
                        }
                        return false;
                    }
                } ) )
                {

                    if ( tmpFile.length() > 0 )
                    {
                        String realPath = tmpFile.getPath().substring( 0, tmpFile.getPath().lastIndexOf( "." ) );

                        FileLockCompanion fileLockCompanion = null;
                        if ( realPath.equals( path ) )
                        {
                            File newFile = tmpFile;
                            synchronized ( activeDownloadFiles )
                            {
                                fileLockCompanion = lockFile( tmpFile );
                                logger.debug( String.format( "Found an incomplete download for file %s.", path ) );

                                if ( fileLockCompanion.getLock() == null )
                                {
                                    /**
                                     * Lock failed so we need to regenerate a new tmp file.
                                     */
                                    newFile = getTmpFile( path );
                                    fileLockCompanion = lockFile( newFile );

                                }
                                return fileLockCompanion;
                            }
                        }
                    }
                }
            }
        }
        return new FileLockCompanion( getTmpFile( path ), null);
    }

    private static class FileLockCompanion {

        private final File file;
        private final FileLock lock;

        public FileLockCompanion( File file, FileLock lock )
        {
            this.file = file;
            this.lock = lock;
        }

        public File getFile()
        {
            return file;
        }

        public FileLock getLock()
        {
            return lock;
        }

    }

    private FileLockCompanion lockFile( File tmpFile )
    {
        try
        {
            if ( activeDownloadFiles.containsKey( tmpFile ) )
            {
                return new FileLockCompanion( tmpFile, null );
            }

            RandomAccessFile tmpLock = new RandomAccessFile( tmpFile.getPath() + ".lock", "rw" );
            FileLock lock = tmpLock.getChannel().tryLock( 0, 1, false );

            if ( lock != null )
            {
                activeDownloadFiles.put( tmpLock, Boolean.TRUE );
            }

            return new FileLockCompanion( tmpFile, lock );
        }
        catch ( OverlappingFileLockException ex )
        {
            return new FileLockCompanion( tmpFile, null );
        }
        catch ( IOException ex )
        {
            return new FileLockCompanion( tmpFile, null );
        }
    }

    private void unlockFile( FileLockCompanion fileLockCompanion )
    {
        if ( !disableResumeSupport )
        {
            fileLockCompanion.getFile().delete();
            try
            {
                if (fileLockCompanion.getLock() != null)
                {
                    fileLockCompanion.getLock().channel().close();
                    fileLockCompanion.getLock().release();

                    activeDownloadFiles.remove( fileLockCompanion.getFile() );
                }
            }
            catch ( IOException e )
            {
                // Ignore.
            }
        }
    }

    private File getTmpFile( String path )
    {
        File file;
        do
        {
            file = new File( path + ".ahc" + UUID.randomUUID().toString().replace( "-", "" ).substring( 0, 16 ) );
        }
        while ( file.exists() );
        return file;
    }

    private static final ExceptionWrapper<MetadataTransfer> METADATA = new ExceptionWrapper<MetadataTransfer>()
    {
        public void wrap( MetadataTransfer transfer, Exception e, RemoteRepository repository )
        {
            MetadataTransferException ex = null;
            if ( e instanceof ResourceDoesNotExistException )
            {
                ex = new MetadataNotFoundException( transfer.getMetadata(), repository );
            }
            else if ( e != null )
            {
                ex = new MetadataTransferException( transfer.getMetadata(), repository, e );
            }
            transfer.setException( ex );
        }
    };

    private static final ExceptionWrapper<ArtifactTransfer> ARTIFACT = new ExceptionWrapper<ArtifactTransfer>()
    {
        public void wrap( ArtifactTransfer transfer, Exception e, RemoteRepository repository )
        {
            ArtifactTransferException ex = null;
            if ( e instanceof ResourceDoesNotExistException )
            {
                ex = new ArtifactNotFoundException( transfer.getArtifact(), repository );
            }
            else if ( e != null )
            {
                ex = new ArtifactTransferException( transfer.getArtifact(), repository, e );
            }
            transfer.setException( ex );
        }
    };

    private class LatchGuard
    {

        private final CountDownLatch latch;

        private final AtomicBoolean done = new AtomicBoolean( false );

        public LatchGuard( CountDownLatch latch )
        {
            this.latch = latch;
        }

        public void countDown()
        {
            if ( !done.getAndSet( true ) )
            {
                latch.countDown();
            }
        }
    }

    private void close( Closeable closeable, File file )
    {
        if ( closeable != null )
        {
            try
            {
                closeable.close();
            }
            catch ( IOException e )
            {
                logger.debug( "Error closing resumable file " + file, e );
            }
        }
    }

}
