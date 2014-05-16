/* -*- Mode: C++; tab-width: 8; indent-tabs-mode: nil; c-basic-offset: 4 -*-
 * vim: set ts=4 sw=4 et tw=80:
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

#include "WrapperOwner.h"
#include "mozilla/dom/BindingUtils.h"
#include "jsfriendapi.h"
#include "xpcprivate.h"

using namespace js;
using namespace JS;
using namespace mozilla;
using namespace mozilla::jsipc;

WrapperOwner::WrapperOwner()
  : inactive_(false)
{
}

static inline WrapperOwner *
OwnerOf(JSObject *obj)
{
    MOZ_ASSERT(IsCPOW(obj));
    return reinterpret_cast<WrapperOwner *>(GetProxyExtra(obj, 0).toPrivate());
}

ObjectId
WrapperOwner::idOf(JSObject *obj)
{
    MOZ_ASSERT(IsCPOW(obj));

    Value v = GetProxyExtra(obj, 1);
    MOZ_ASSERT(v.isDouble());

    ObjectId objId = BitwiseCast<uint64_t>(v.toDouble());
    MOZ_ASSERT(findCPOWById(objId) == obj);
    MOZ_ASSERT(objId);

    return objId;
}

int sCPOWProxyHandler;

class CPOWProxyHandler : public BaseProxyHandler
{
  public:
    CPOWProxyHandler()
      : BaseProxyHandler(&sCPOWProxyHandler) {}
    virtual ~CPOWProxyHandler() {}

    virtual bool finalizeInBackground(Value priv) MOZ_OVERRIDE {
        return false;
    }

    virtual bool preventExtensions(JSContext *cx, HandleObject proxy) MOZ_OVERRIDE;
    virtual bool getPropertyDescriptor(JSContext *cx, HandleObject proxy, HandleId id,
                                       MutableHandle<JSPropertyDescriptor> desc) MOZ_OVERRIDE;
    virtual bool getOwnPropertyDescriptor(JSContext *cx, HandleObject proxy, HandleId id,
                                          MutableHandle<JSPropertyDescriptor> desc) MOZ_OVERRIDE;
    virtual bool defineProperty(JSContext *cx, HandleObject proxy, HandleId id,
                                MutableHandle<JSPropertyDescriptor> desc) MOZ_OVERRIDE;
    virtual bool getOwnPropertyNames(JSContext *cx, HandleObject proxy,
                                     AutoIdVector &props) MOZ_OVERRIDE;
    virtual bool delete_(JSContext *cx, HandleObject proxy, HandleId id, bool *bp) MOZ_OVERRIDE;
    virtual bool enumerate(JSContext *cx, HandleObject proxy, AutoIdVector &props) MOZ_OVERRIDE;

    virtual bool has(JSContext *cx, HandleObject proxy, HandleId id, bool *bp) MOZ_OVERRIDE;
    virtual bool hasOwn(JSContext *cx, HandleObject proxy, HandleId id, bool *bp) MOZ_OVERRIDE;
    virtual bool get(JSContext *cx, HandleObject proxy, HandleObject receiver,
                     HandleId id, MutableHandleValue vp) MOZ_OVERRIDE;
    virtual bool set(JSContext *cx, JS::HandleObject proxy, JS::HandleObject receiver,
                     JS::HandleId id, bool strict, JS::MutableHandleValue vp) MOZ_OVERRIDE;
    virtual bool keys(JSContext *cx, HandleObject proxy, AutoIdVector &props) MOZ_OVERRIDE;

    virtual bool isExtensible(JSContext *cx, HandleObject proxy, bool *extensible) MOZ_OVERRIDE;
    virtual bool call(JSContext *cx, HandleObject proxy, const CallArgs &args) MOZ_OVERRIDE;
    virtual bool objectClassIs(HandleObject obj, js::ESClassValue classValue, JSContext *cx) MOZ_OVERRIDE;
    virtual const char* className(JSContext *cx, HandleObject proxy) MOZ_OVERRIDE;
    virtual void finalize(JSFreeOp *fop, JSObject *proxy) MOZ_OVERRIDE;

    static CPOWProxyHandler singleton;
};

CPOWProxyHandler CPOWProxyHandler::singleton;

/* static */ BaseProxyHandler *
WrapperOwner::ProxyHandler()
{
    return &CPOWProxyHandler::singleton;
}

#define FORWARD(call, args)                                             \
    WrapperOwner *owner = OwnerOf(proxy);                               \
    if (!owner->active()) {                                             \
        JS_ReportError(cx, "cannot use a CPOW whose process is gone");  \
        return false;                                                   \
    }                                                                   \
    return owner->call args;

bool
CPOWProxyHandler::preventExtensions(JSContext *cx, HandleObject proxy)
{
    FORWARD(preventExtensions, (cx, proxy));
}

bool
WrapperOwner::preventExtensions(JSContext *cx, HandleObject proxy)
{
    ObjectId objId = idOf(proxy);

    ReturnStatus status;
    if (!CallPreventExtensions(objId, &status))
        return ipcfail(cx);

    return ok(cx, status);
}

bool
CPOWProxyHandler::getPropertyDescriptor(JSContext *cx, HandleObject proxy, HandleId id,
                                        MutableHandle<JSPropertyDescriptor> desc)
{
    FORWARD(getPropertyDescriptor, (cx, proxy, id, desc));
}

bool
WrapperOwner::getPropertyDescriptor(JSContext *cx, HandleObject proxy, HandleId id,
				    MutableHandle<JSPropertyDescriptor> desc)
{
    ObjectId objId = idOf(proxy);

    nsString idstr;
    if (!convertIdToGeckoString(cx, id, &idstr))
        return false;

    ReturnStatus status;
    PPropertyDescriptor result;
    if (!CallGetPropertyDescriptor(objId, idstr, &status, &result))
        return ipcfail(cx);
    if (!ok(cx, status))
        return false;

    return toDescriptor(cx, result, desc);
}

bool
CPOWProxyHandler::getOwnPropertyDescriptor(JSContext *cx, HandleObject proxy, HandleId id,
                                           MutableHandle<JSPropertyDescriptor> desc)
{
    FORWARD(getOwnPropertyDescriptor, (cx, proxy, id, desc));
}

bool
WrapperOwner::getOwnPropertyDescriptor(JSContext *cx, HandleObject proxy, HandleId id,
				       MutableHandle<JSPropertyDescriptor> desc)
{
    ObjectId objId = idOf(proxy);

    nsString idstr;
    if (!convertIdToGeckoString(cx, id, &idstr))
        return false;

    ReturnStatus status;
    PPropertyDescriptor result;
    if (!CallGetOwnPropertyDescriptor(objId, idstr, &status, &result))
        return ipcfail(cx);
    if (!ok(cx, status))
        return false;

    return toDescriptor(cx, result, desc);
}

bool
CPOWProxyHandler::defineProperty(JSContext *cx, HandleObject proxy, HandleId id,
                                 MutableHandle<JSPropertyDescriptor> desc)
{
    FORWARD(defineProperty, (cx, proxy, id, desc));
}

bool
WrapperOwner::defineProperty(JSContext *cx, HandleObject proxy, HandleId id,
			     MutableHandle<JSPropertyDescriptor> desc)
{
    ObjectId objId = idOf(proxy);

    nsString idstr;
    if (!convertIdToGeckoString(cx, id, &idstr))
        return false;

    PPropertyDescriptor descriptor;
    if (!fromDescriptor(cx, desc, &descriptor))
        return false;

    ReturnStatus status;
    if (!CallDefineProperty(objId, idstr, descriptor, &status))
        return ipcfail(cx);

    return ok(cx, status);
}

bool
CPOWProxyHandler::getOwnPropertyNames(JSContext *cx, HandleObject proxy, AutoIdVector &props)
{
    FORWARD(getOwnPropertyNames, (cx, proxy, props));
}

bool
WrapperOwner::getOwnPropertyNames(JSContext *cx, HandleObject proxy, AutoIdVector &props)
{
    return getPropertyNames(cx, proxy, JSITER_OWNONLY | JSITER_HIDDEN, props);
}

bool
CPOWProxyHandler::delete_(JSContext *cx, HandleObject proxy, HandleId id, bool *bp)
{
    FORWARD(delete_, (cx, proxy, id, bp));
}

bool
WrapperOwner::delete_(JSContext *cx, HandleObject proxy, HandleId id, bool *bp)
{
    ObjectId objId = idOf(proxy);

    nsString idstr;
    if (!convertIdToGeckoString(cx, id, &idstr))
        return false;

    ReturnStatus status;
    if (!CallDelete(objId, idstr, &status, bp))
        return ipcfail(cx);

    return ok(cx, status);
}

bool
CPOWProxyHandler::enumerate(JSContext *cx, HandleObject proxy, AutoIdVector &props)
{
    FORWARD(enumerate, (cx, proxy, props));
}

bool
WrapperOwner::enumerate(JSContext *cx, HandleObject proxy, AutoIdVector &props)
{
    return getPropertyNames(cx, proxy, 0, props);
}

bool
CPOWProxyHandler::has(JSContext *cx, HandleObject proxy, HandleId id, bool *bp)
{
    FORWARD(has, (cx, proxy, id, bp));
}

bool
WrapperOwner::has(JSContext *cx, HandleObject proxy, HandleId id, bool *bp)
{
    ObjectId objId = idOf(proxy);

    nsString idstr;
    if (!convertIdToGeckoString(cx, id, &idstr))
        return false;

    ReturnStatus status;
    if (!CallHas(objId, idstr, &status, bp))
        return ipcfail(cx);

    return ok(cx, status);
}

bool
CPOWProxyHandler::hasOwn(JSContext *cx, HandleObject proxy, HandleId id, bool *bp)
{
    FORWARD(hasOwn, (cx, proxy, id, bp));
}

bool
WrapperOwner::hasOwn(JSContext *cx, HandleObject proxy, HandleId id, bool *bp)
{
    ObjectId objId = idOf(proxy);

    nsString idstr;
    if (!convertIdToGeckoString(cx, id, &idstr))
        return false;

    ReturnStatus status;
    if (!CallHasOwn(objId, idstr, &status, bp))
        return ipcfail(cx);

    return !!ok(cx, status);
}

bool
CPOWProxyHandler::get(JSContext *cx, HandleObject proxy, HandleObject receiver,
                      HandleId id, MutableHandleValue vp)
{
    FORWARD(get, (cx, proxy, receiver, id, vp));
}

bool
WrapperOwner::get(JSContext *cx, HandleObject proxy, HandleObject receiver,
		  HandleId id, MutableHandleValue vp)
{
    ObjectId objId = idOf(proxy);
    ObjectId receiverId = idOf(receiver);

    nsString idstr;
    if (!convertIdToGeckoString(cx, id, &idstr))
        return false;

    JSVariant val;
    ReturnStatus status;
    if (!CallGet(objId, receiverId, idstr, &status, &val))
        return ipcfail(cx);

    if (!ok(cx, status))
        return false;

    return fromVariant(cx, val, vp);
}

bool
CPOWProxyHandler::set(JSContext *cx, JS::HandleObject proxy, JS::HandleObject receiver,
                      JS::HandleId id, bool strict, JS::MutableHandleValue vp)
{
    FORWARD(set, (cx, proxy, receiver, id, strict, vp));
}

bool
WrapperOwner::set(JSContext *cx, JS::HandleObject proxy, JS::HandleObject receiver,
		  JS::HandleId id, bool strict, JS::MutableHandleValue vp)
{
    ObjectId objId = idOf(proxy);
    ObjectId receiverId = idOf(receiver);

    nsString idstr;
    if (!convertIdToGeckoString(cx, id, &idstr))
        return false;

    JSVariant val;
    if (!toVariant(cx, vp, &val))
        return false;

    ReturnStatus status;
    JSVariant result;
    if (!CallSet(objId, receiverId, idstr, strict, val, &status, &result))
        return ipcfail(cx);

    if (!ok(cx, status))
        return false;

    return fromVariant(cx, result, vp);
}

bool
CPOWProxyHandler::keys(JSContext *cx, HandleObject proxy, AutoIdVector &props)
{
    FORWARD(keys, (cx, proxy, props));
}

bool
WrapperOwner::keys(JSContext *cx, HandleObject proxy, AutoIdVector &props)
{
    return getPropertyNames(cx, proxy, JSITER_OWNONLY, props);
}

bool
CPOWProxyHandler::isExtensible(JSContext *cx, HandleObject proxy, bool *extensible)
{
    FORWARD(isExtensible, (cx, proxy, extensible));
}

bool
WrapperOwner::isExtensible(JSContext *cx, HandleObject proxy, bool *extensible)
{
    ObjectId objId = idOf(proxy);

    ReturnStatus status;
    if (!CallIsExtensible(objId, &status, extensible))
        return ipcfail(cx);

    return ok(cx, status);
}

bool
CPOWProxyHandler::call(JSContext *cx, HandleObject proxy, const CallArgs &args)
{
    FORWARD(call, (cx, proxy, args));
}

bool
WrapperOwner::call(JSContext *cx, HandleObject proxy, const CallArgs &args)
{
    ObjectId objId = idOf(proxy);

    InfallibleTArray<JSParam> vals;
    AutoValueVector outobjects(cx);

    RootedValue v(cx);
    for (size_t i = 0; i < args.length() + 2; i++) {
        v = args.base()[i];
        if (v.isObject()) {
            RootedObject obj(cx, &v.toObject());
            if (xpc::IsOutObject(cx, obj)) {
                // Make sure it is not an in-out object.
                bool found;
                if (!JS_HasProperty(cx, obj, "value", &found))
                    return false;
                if (found) {
                    JS_ReportError(cx, "in-out objects cannot be sent via CPOWs yet");
                    return false;
                }

                vals.AppendElement(JSParam(void_t()));
                if (!outobjects.append(ObjectValue(*obj)))
                    return false;
                continue;
            }
        }
        JSVariant val;
        if (!toVariant(cx, v, &val))
            return false;
        vals.AppendElement(JSParam(val));
    }

    JSVariant result;
    ReturnStatus status;
    InfallibleTArray<JSParam> outparams;
    if (!CallCall(objId, vals, &status, &result, &outparams))
        return ipcfail(cx);
    if (!ok(cx, status))
        return false;

    if (outparams.Length() != outobjects.length())
        return ipcfail(cx);

    RootedObject obj(cx);
    for (size_t i = 0; i < outparams.Length(); i++) {
        // Don't bother doing anything for outparams that weren't set.
        if (outparams[i].type() == JSParam::Tvoid_t)
            continue;

        // Take the value the child process returned, and set it on the XPC
        // object.
        if (!fromVariant(cx, outparams[i], &v))
            return false;

        obj = &outobjects[i].toObject();
        if (!JS_SetProperty(cx, obj, "value", v))
            return false;
    }

    if (!fromVariant(cx, result, args.rval()))
        return false;

    return true;
}


bool
CPOWProxyHandler::objectClassIs(HandleObject proxy, js::ESClassValue classValue, JSContext *cx)
{
    FORWARD(objectClassIs, (cx, proxy, classValue));
}

bool
WrapperOwner::objectClassIs(JSContext *cx, HandleObject proxy, js::ESClassValue classValue)
{
    ObjectId objId = idOf(proxy);

    // This function is assumed infallible, so we just return false if the IPC
    // channel fails.
    bool result;
    if (!CallObjectClassIs(objId, classValue, &result))
        return false;

    return result;
}

const char *
CPOWProxyHandler::className(JSContext *cx, HandleObject proxy)
{
    WrapperOwner *parent = OwnerOf(proxy);
    if (!parent->active())
        return "<dead CPOW>";
    return parent->className(cx, proxy);
}

const char *
WrapperOwner::className(JSContext *cx, HandleObject proxy)
{
    ObjectId objId = idOf(proxy);

    nsString name;
    if (!CallClassName(objId, &name))
        return "<error>";

    return ToNewCString(name);
}

void
CPOWProxyHandler::finalize(JSFreeOp *fop, JSObject *proxy)
{
    OwnerOf(proxy)->drop(proxy);
}

bool
WrapperOwner::init()
{
    if (!JavaScriptShared::init())
        return false;

    return true;
}

bool
WrapperOwner::getPropertyNames(JSContext *cx, HandleObject proxy, uint32_t flags, AutoIdVector &props)
{
    ObjectId objId = idOf(proxy);

    ReturnStatus status;
    InfallibleTArray<nsString> names;
    if (!CallGetPropertyNames(objId, flags, &status, &names))
        return ipcfail(cx);
    if (!ok(cx, status))
        return false;

    for (size_t i = 0; i < names.Length(); i++) {
        RootedId name(cx);
        if (!convertGeckoStringToId(cx, names[i], &name))
            return false;
        if (!props.append(name))
            return false;
    }

    return true;
}

namespace mozilla {
namespace jsipc {

bool
IsCPOW(JSObject *obj)
{
    return IsProxy(obj) && GetProxyHandler(obj) == &CPOWProxyHandler::singleton;
}

nsresult
InstanceOf(JSObject *proxy, const nsID *id, bool *bp)
{
    WrapperOwner *parent = OwnerOf(proxy);
    if (!parent->active())
        return NS_ERROR_UNEXPECTED;
    return parent->instanceOf(proxy, id, bp);
}

bool
DOMInstanceOf(JSContext *cx, JSObject *proxy, int prototypeID, int depth, bool *bp)
{
    FORWARD(domInstanceOf, (cx, proxy, prototypeID, depth, bp));
}

} /* namespace jsipc */
} /* namespace mozilla */

nsresult
WrapperOwner::instanceOf(JSObject *obj, const nsID *id, bool *bp)
{
    ObjectId objId = idOf(obj);

    JSIID iid;
    ConvertID(*id, &iid);

    ReturnStatus status;
    if (!CallInstanceOf(objId, iid, &status, bp))
        return NS_ERROR_UNEXPECTED;

    if (status.type() != ReturnStatus::TReturnSuccess)
        return NS_ERROR_UNEXPECTED;

    return NS_OK;
}

bool
WrapperOwner::domInstanceOf(JSContext *cx, JSObject *obj, int prototypeID, int depth, bool *bp)
{
    ObjectId objId = idOf(obj);

    ReturnStatus status;
    if (!CallDOMInstanceOf(objId, prototypeID, depth, &status, bp))
        return ipcfail(cx);

    return ok(cx, status);
}

void
WrapperOwner::ActorDestroy(ActorDestroyReason why)
{
    inactive_ = true;
}

bool
WrapperOwner::ipcfail(JSContext *cx)
{
    JS_ReportError(cx, "child process crashed or timedout");
    return false;
}

bool
WrapperOwner::ok(JSContext *cx, const ReturnStatus &status)
{
    if (status.type() == ReturnStatus::TReturnSuccess)
        return true;

    if (status.type() == ReturnStatus::TReturnStopIteration)
        return JS_ThrowStopIteration(cx);

    RootedValue exn(cx);
    if (!fromVariant(cx, status.get_ReturnException().exn(), &exn))
        return false;

    JS_SetPendingException(cx, exn);
    return false;
}
