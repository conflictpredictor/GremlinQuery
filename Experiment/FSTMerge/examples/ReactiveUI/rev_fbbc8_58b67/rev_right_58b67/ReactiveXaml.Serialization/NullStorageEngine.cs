﻿using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;

namespace ReactiveXaml.Serialization
{
    public class NullStorageEngine : IStorageEngine
    {
        public T Load<T>(Guid ContentHash) where T : ISerializableItemBase
        {
            this.Log().DebugFormat("Loading {0}, returning null", ContentHash);
            return default(T);
        }

        public object Load(Guid ContentHash) 
        {
            this.Log().DebugFormat("Loading {0}, returning null", ContentHash);
            return null;
        }

        public void Save<T>(T Obj) where T : ISerializableItemBase
        {
            this.Log().DebugFormat("Saving {0}", Obj.ContentHash);
        }

        public void FlushChanges()
        {
            this.Log().Debug("Flush");
        }

        public ISyncPointInformation CreateSyncPoint<T>(T obj, string qualifier = null, DateTimeOffset? createdOn = null) 
            where T : ISerializableItemBase
        {
            this.Log().DebugFormat("Creating sync point for {0} ({1})", obj.ContentHash, qualifier);
            return new SyncPointInformation(Guid.Empty, Guid.Empty, typeof (T), qualifier ?? String.Empty, createdOn ?? DateTimeOffset.Now);
        }

        public Guid[] GetOrderedRevisionList(Type type, string qualifier = null) 
        {
            return null;
        }

        public void Dispose()
        {
        }
    }
}