/*******************************************************************************
 * Copyright (c) 2009  Eucalyptus Systems, Inc.
 * 
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, only version 3 of the License.
 * 
 * 
 *  This file is distributed in the hope that it will be useful, but WITHOUT
 *  ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 *  FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 *  for more details.
 * 
 *  You should have received a copy of the GNU General Public License along
 *  with this program.  If not, see <http://www.gnu.org/licenses/>.
 * 
 *  Please contact Eucalyptus Systems, Inc., 130 Castilian
 *  Dr., Goleta, CA 93101 USA or visit <http://www.eucalyptus.com/licenses/>
 *  if you need additional information or have any questions.
 * 
 *  This file may incorporate work covered under the following copyright and
 *  permission notice:
 * 
 *    Software License Agreement (BSD License)
 * 
 *    Copyright (c) 2008, Regents of the University of California
 *    All rights reserved.
 * 
 *    Redistribution and use of this software in source and binary forms, with
 *    or without modification, are permitted provided that the following
 *    conditions are met:
 * 
 *      Redistributions of source code must retain the above copyright notice,
 *      this list of conditions and the following disclaimer.
 * 
 *      Redistributions in binary form must reproduce the above copyright
 *      notice, this list of conditions and the following disclaimer in the
 *      documentation and/or other materials provided with the distribution.
 * 
 *    THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS
 *    IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED
 *    TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A
 *    PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER
 *    OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 *    EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 *    PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
 *    PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
 *    LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 *    NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 *    SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE. USERS OF
 *    THIS SOFTWARE ACKNOWLEDGE THE POSSIBLE PRESENCE OF OTHER OPEN SOURCE
 *    LICENSED MATERIAL, COPYRIGHTED MATERIAL OR PATENTED MATERIAL IN THIS
 *    SOFTWARE, AND IF ANY SUCH MATERIAL IS DISCOVERED THE PARTY DISCOVERING
 *    IT MAY INFORM DR. RICH WOLSKI AT THE UNIVERSITY OF CALIFORNIA, SANTA
 *    BARBARA WHO WILL THEN ASCERTAIN THE MOST APPROPRIATE REMEDY, WHICH IN
 *    THE REGENTS' DISCRETION MAY INCLUDE, WITHOUT LIMITATION, REPLACEMENT
 *    OF THE CODE SO IDENTIFIED, LICENSING OF THE CODE SO IDENTIFIED, OR
 *    WITHDRAWAL OF THE CODE CAPABILITY TO THE EXTENT NEEDED TO COMPLY WITH
 *    ANY SUCH LICENSES OR RIGHTS.
 *******************************************************************************
 * @author chris grzegorczyk <grze@eucalyptus.com>
 */

package com.eucalyptus.blockstorage;

import java.lang.reflect.UndeclaredThrowableException;
import java.util.concurrent.ExecutionException;
import org.apache.log4j.Logger;
import com.eucalyptus.auth.principal.UserFullName;
import com.eucalyptus.component.Partitions;
import com.eucalyptus.component.ServiceConfiguration;
import com.eucalyptus.component.id.Storage;
import com.eucalyptus.crypto.Crypto;
import com.eucalyptus.entities.Transactions;
import com.eucalyptus.event.ListenerRegistry;
import com.eucalyptus.reporting.event.StorageEvent;
import com.eucalyptus.util.Callback;
import com.eucalyptus.util.EucalyptusCloudException;
import com.eucalyptus.ws.client.ServiceDispatcher;
import com.google.common.collect.Lists;
import edu.ucsb.eucalyptus.msgs.BaseMessage;
import edu.ucsb.eucalyptus.msgs.CreateStorageVolumeType;
import edu.ucsb.eucalyptus.msgs.DescribeStorageVolumesResponseType;
import edu.ucsb.eucalyptus.msgs.DescribeStorageVolumesType;

public class Volumes {
  private static Logger LOG       = Logger.getLogger( Volumes.class );
  private static String ID_PREFIX = "vol";
  
  public static Volume checkVolumeReady( final Volume vol ) throws EucalyptusCloudException {
    if ( vol.isReady( ) ) {
      return vol;
    } else {
      //TODO:GRZE:REMOVE temporary workaround to update the volume state.
      final ServiceConfiguration sc = Partitions.lookupService( Storage.class, vol.getPartition( ) );
      final DescribeStorageVolumesType descVols = new DescribeStorageVolumesType( Lists.newArrayList( vol.getDisplayName( ) ) );
      try {
        Transactions.one( Volume.named( vol.getDisplayName( ) ), new Callback<Volume>( ) {
          
          @Override
          public void fire( Volume t ) {
            try {
              DescribeStorageVolumesResponseType volState = ServiceDispatcher.lookup( sc ).send( descVols );
              if ( !volState.getVolumeSet( ).isEmpty( ) ) {
                vol.setMappedState( volState.getVolumeSet( ).get( 0 ).getStatus( ) );
              }
            } catch ( EucalyptusCloudException ex ) {
              LOG.error( ex, ex );
              throw new UndeclaredThrowableException( ex, "Failed to update the volume state " + vol.getDisplayName( ) + " not yet ready" );
            }
          }
        } );
      } catch ( ExecutionException ex ) {
        throw new EucalyptusCloudException( ex.getCause( ) );
      }
      if ( !vol.isReady( ) ) {
        throw new EucalyptusCloudException( "Volume " + vol.getDisplayName( ) + " not yet ready" );
      }
      return vol;
    }
  }
  
  public static Volume createStorageVolume( final ServiceConfiguration sc, UserFullName owner, final String snapId, Integer newSize, final BaseMessage request ) throws ExecutionException {
    String newId = Crypto.generateId( owner.getAccountNumber( ), ID_PREFIX );
    Volume newVol = Transactions.save( new Volume( owner, newId, newSize, sc.getName( ), sc.getPartition( ), snapId ), new Callback<Volume>( ) {
      
      @Override
      public void fire( Volume t ) {
        t.setState( State.GENERATING );
        try {
          ListenerRegistry.getInstance( ).fireEvent( new StorageEvent( StorageEvent.EventType.EbsVolume, true, t.getSize( ),
                                                                       t.getOwnerUserId( ), t.getOwnerUserName( ),
                                                                       t.getOwnerAccountNumber( ), t.getOwnerAccountName( ),
                                                                       t.getScName( ), t.getPartition( ) ) );
          CreateStorageVolumeType req = new CreateStorageVolumeType( t.getDisplayName( ), t.getSize( ), snapId, null ).regardingUserRequest( request );
          ServiceDispatcher.lookup( sc ).send( req );
        } catch ( Exception ex ) {
          LOG.error( "Failed to create volume: " + t.toString( ), ex );
          throw new UndeclaredThrowableException( ex );
        }
      }
    } );
    return newVol;
  }
}
