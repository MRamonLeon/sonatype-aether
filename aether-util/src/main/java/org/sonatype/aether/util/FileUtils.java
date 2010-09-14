package org.sonatype.aether.util;

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

import java.io.File;
import java.io.IOException;

/**
 * A utility class helping with file-based operations.
 * 
 * @author Benjamin Hanzelmann
 */
public class FileUtils
{

    /**
     * Thread-safe variant of {@link File#mkdirs()}. Adapted from Java 6. Creates the directory named by the given
     * abstract pathname, including any necessary but nonexistent parent directories. Note that if this operation fails
     * it may have succeeded in creating some of the necessary parent directories.
     * 
     * @param dir the directory to create.
     * @return <code>true</code> if and only if the directory was created, along with all necessary parent directories;
     *         <code>false</code> otherwise
     */
    public static boolean mkdirs( File dir )
    {
        if ( dir.exists() )
        {
            return false;
        }
        if ( dir.mkdir() )
        {
            return true;
        }

        File canonFile = null;
        try
        {
            canonFile = dir.getCanonicalFile();
        }
        catch ( IOException e )
        {
            return false;
        }

        File parent = canonFile.getParentFile();
        return ( parent != null && ( mkdirs( parent ) || parent.exists() ) && canonFile.mkdir() );
    }

}