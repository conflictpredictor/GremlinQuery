/*
 * Copyright 2011 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.channel.socket; 

import com.sun.nio.sctp.Notification; 

  class  SctpNotification   {
	
    ~~FSTMerge~~ private final Notification notification; ##FSTMerge## private Notification notification; ##FSTMerge##

	
    ~~FSTMerge~~ private final Object attachment; ##FSTMerge## private Object attachment; ##FSTMerge##

	


    

	

    /**
     * Return the {@link Notification}
     */
    

	

    /**
     * Return the attachment of this {@link SctpNotification}, or
     * <code>null</code> if no attachment was provided
     */
    

	

    

	

    

	

    


}
