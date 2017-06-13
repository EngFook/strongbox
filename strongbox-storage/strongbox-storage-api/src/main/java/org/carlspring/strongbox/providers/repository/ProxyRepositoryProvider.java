package org.carlspring.strongbox.providers.repository;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.security.NoSuchAlgorithmException;

import javax.annotation.PostConstruct;
import javax.inject.Inject;
import javax.ws.rs.core.Response;

import org.carlspring.commons.io.MultipleDigestInputStream;
import org.carlspring.strongbox.client.ArtifactResolver;
import org.carlspring.strongbox.client.ArtifactTransportException;
import org.carlspring.strongbox.configuration.Configuration;
import org.carlspring.strongbox.configuration.ConfigurationManager;
import org.carlspring.strongbox.io.ArtifactInputStream;
import org.carlspring.strongbox.io.ArtifactOutputStream;
import org.carlspring.strongbox.providers.ProviderImplementationException;
import org.carlspring.strongbox.providers.io.RepositoryFileSystemProvider;
import org.carlspring.strongbox.providers.io.RepositoryPath;
import org.carlspring.strongbox.providers.layout.LayoutProvider;
import org.carlspring.strongbox.providers.layout.LayoutProviderRegistry;
import org.carlspring.strongbox.providers.repository.proxy.RemoteRepositoryRegistry;
import org.carlspring.strongbox.providers.repository.proxy.RemoteRepositoryStatusEnum;
import org.carlspring.strongbox.providers.repository.proxy.RemoteRepositoryStatusInfo;
import org.carlspring.strongbox.service.ProxyRepositoryConnectionPoolConfigurationService;
import org.carlspring.strongbox.storage.Storage;
import org.carlspring.strongbox.storage.repository.RemoteRepository;
import org.carlspring.strongbox.storage.repository.Repository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * @author carlspring
 */
@Component
public class ProxyRepositoryProvider extends AbstractRepositoryProvider
{

    private static final Logger logger = LoggerFactory.getLogger(ProxyRepositoryProvider.class);

    private static final String ALIAS = "proxy";

    @Inject
    private RepositoryProviderRegistry repositoryProviderRegistry;

    @Inject
    private ProxyRepositoryConnectionPoolConfigurationService proxyRepositoryConnectionPoolConfigurationService;

    @Inject
    private LayoutProviderRegistry layoutProviderRegistry;

    @Inject
    private RemoteRepositoryRegistry remoteRepositoryRegistry;

    @Inject
    private ConfigurationManager configurationManager;


    @PostConstruct
    @Override
    public void register()
    {
        repositoryProviderRegistry.addProvider(ALIAS, this);

        logger.info("Registered repository provider '" + getClass().getCanonicalName() +
                    "' with alias '" + ALIAS + "'.");

        for (Storage storage : getConfiguration().getStorages().values())
        {
            for (Repository repository : storage.getRepositories().values())
            {
                if (repository.isProxyRepository())
                {
                    RemoteRepository remoteRepository = repository.getRemoteRepository();

                    RemoteRepositoryStatusInfo info = new RemoteRepositoryStatusInfo();
                    info.setStatus(RemoteRepositoryStatusEnum.UNKNOWN.getStatus());
                    info.setUrl(remoteRepository.getUrl());

                    remoteRepositoryRegistry.addRepositoryInfo(info);

                    logger.debug("Registered " + remoteRepository.getUrl() + " " +
                                 "(timeout = " + remoteRepository.getTimeout() + "," +
                                 " retries = " + remoteRepository.getRetries() + " , " +
                                 " remoteHostCheckInterval = " + remoteRepository.getRemoteHostCheckInterval() + ")" +
                                 " with the remote repository registry.");
                }
            }
        }
    }

    @Override
    public String getAlias()
    {
        return ALIAS;
    }

    @Override
    public ArtifactInputStream getInputStream(String storageId,
                                              String repositoryId,
                                              String path)
            throws IOException,
                   NoSuchAlgorithmException,
                   ArtifactTransportException,
                   ProviderImplementationException
    {
        Storage storage = getConfiguration().getStorage(storageId);
        Repository repository = storage.getRepository(repositoryId);

        logger.debug("Checking in " + storage.getId() + ":" + repositoryId + "...");

        LayoutProvider layoutProvider = layoutProviderRegistry.getProvider(repository.getLayout());
        RepositoryPath reposytoryPath = layoutProvider.resolve(repository);
        RepositoryPath artifactPath = reposytoryPath.resolve(path);

        RepositoryFileSystemProvider fileSystemProvider = artifactPath.getFileSystem().provider();

        logger.debug(" -> Checking for " + artifactPath + "...");

        if (layoutProvider.containsPath(repository, path))
        {
            logger.debug("The artifact was found in the local cache.");
            logger.debug("Resolved " + artifactPath + "!");

            RepositoryPath repositoryPath = layoutProvider.resolve(repository).resolve(path);
            return (ArtifactInputStream) Files.newInputStream(repositoryPath);
        }
        else
        {
            logger.debug("The artifact was not found in the local cache.");

            RemoteRepository remoteRepository = repository.getRemoteRepository();

            ArtifactResolver client = new ArtifactResolver(proxyRepositoryConnectionPoolConfigurationService.getClient());
            client.setRepositoryBaseUrl(remoteRepository.getUrl());
            client.setUsername(remoteRepository.getUsername());
            client.setPassword(remoteRepository.getPassword());

            Response response = client.getResourceWithResponse(path);
            if (response.getStatus() != 200 || response.getEntity() == null)
            {
                return null;
            }

            InputStream is = response.readEntity(InputStream.class);
            if (is == null)
            {
                return null;
            }

            RepositoryPath tempArtifact = fileSystemProvider.getTempPath(artifactPath);
            try (InputStream remoteIs = new MultipleDigestInputStream(is);
                 // Wrap the InputStream, so we could have checksums to compare
                 OutputStream os = fileSystemProvider.newOutputStream(tempArtifact))
            {
                layoutProvider.getArtifactManagementService().store(storageId, repositoryId, path, remoteIs, os);

                // TODO: Add a policy for validating the checksums of downloaded artifacts
                // TODO: Validate the local checksum against the remote's checksums
                fileSystemProvider.moveFromTemporaryDirectory(artifactPath);

                // Serve the downloaded artifact
                RepositoryPath repositoryPath = layoutProvider.resolve(repository).resolve(path);
                return (ArtifactInputStream) Files.newInputStream(repositoryPath);
            }
        }
    }

    @Override
    public ArtifactOutputStream getOutputStream(String storageId,
                                                String repositoryId,
                                                String artifactPath)
            throws IOException,
                   NoSuchAlgorithmException
    {
        Storage storage = getConfiguration().getStorage(storageId);
        Repository repository = storage.getRepository(repositoryId);

        LayoutProvider layoutProvider = layoutProviderRegistry.getProvider(repository.getLayout());
        RepositoryPath repositoryPath = layoutProvider.resolve(repository).resolve(artifactPath);

        return (ArtifactOutputStream) Files.newOutputStream(repositoryPath);
    }

    @Override
    public Configuration getConfiguration()
    {
        return configurationManager.getConfiguration();
    }

}
