/**
 * Copyright 2013 Peergreen S.A.S.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.peergreen.deployment.scanner;

import java.io.File;
import java.io.Serializable;
import java.util.Comparator;

/**
 * Sort a list of files by using an alphabetical order.
 * @author Florent BENOIT
 */
public class AlphabeticalOrder implements Serializable, Comparator<File>  {

    /**
     * Serial version UID.
     */
    private static final long serialVersionUID = -716674971240669112L;

    /**
     * Compare two files.
     * @param file1 the first file to compare
     * @param file2 the second file to compare.
     * @return compare mode between
     */
    @Override
    public int compare(final File file1, final File file2) {
        return file1.getName().compareToIgnoreCase(file2.getName());
    }
}
