package org.sonatype.maven.repository.spi;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import java.util.Collection;
import java.util.List;

import org.sonatype.maven.repository.ArtifactRequest;
import org.sonatype.maven.repository.ArtifactResolutionException;
import org.sonatype.maven.repository.ArtifactResult;
import org.sonatype.maven.repository.RepositorySystemSession;

/**
 * @author Benjamin Bentmann
 */
public interface ArtifactResolver
{

    /**
     * Resolves the path for an artifact. The artifact will be downloaded if necessary.
     */
    ArtifactResult resolveArtifact( RepositorySystemSession session, ArtifactRequest request )
        throws ArtifactResolutionException;

    /**
     * Resolves the paths for a collection of artifacts. Artifacts will be downloaded if necessary.
     */
    List<ArtifactResult> resolveArtifacts( RepositorySystemSession session, Collection<? extends ArtifactRequest> requests )
        throws ArtifactResolutionException;

}